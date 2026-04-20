package dev.daisybase.jdbc;

import dev.daisybase.common.Common;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.engine.EngineIntrospection;
import dev.daisybase.engine.RemoteProtocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class RemoteDaisyBaseTransport implements DaisyBaseTransport {
    private final DaisyBaseUrl url;
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final String sessionToken;
    private int maxFrameBytes;
    private boolean closed;

    RemoteDaisyBaseTransport(DaisyBaseUrl url) throws SQLException {
        try {
            this.url = url;
            this.socket = new Socket();
            socket.connect(new InetSocketAddress(url.host(), url.port()), url.connectTimeoutMillis());
            socket.setSoTimeout(url.socketTimeoutMillis());
            socket.setTcpNoDelay(true);
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
            this.maxFrameBytes = url.maxFrameBytes();
            RemoteProtocol.write(output, new RemoteProtocol.ClientHello(
                    url.clientName(), RemoteProtocol.VERSION, url.user(), url.password()), maxFrameBytes);
            RemoteProtocol.Message response = RemoteProtocol.read(input, maxFrameBytes);
            if (response instanceof RemoteProtocol.ServerHello serverHello) {
                this.maxFrameBytes = Math.min(this.maxFrameBytes, serverHello.maxFrameBytes());
                this.sessionToken = serverHello.sessionToken();
                return;
            }
            if (response instanceof RemoteProtocol.Failure failure) {
                throw DaisyBaseExceptionFactory.fromFailure(failure);
            }
            throw new SQLException("Unexpected handshake response: " + (response == null ? "null" : response.getClass().getSimpleName()));
        } catch (IOException exception) {
            throw DaisyBaseExceptionFactory.fromException(exception);
        }
    }

    @Override
    public long nextExecutionId() {
        return nextRequestId.getAndIncrement();
    }

    @Override
    public synchronized EngineApi.BatchResult execute(String sql, long executionId, long timeoutMillis) throws SQLException {
        RemoteProtocol.ExecuteResult result = exchange(new RemoteProtocol.ExecuteRequest(executionId, sql, timeoutMillis),
                RemoteProtocol.ExecuteResult.class);
        return result.result();
    }

    @Override
    public synchronized PreparedHandle prepare(String sql) throws SQLException {
        RemoteProtocol.PrepareResult result = exchange(new RemoteProtocol.PrepareRequest(nextRequestId.getAndIncrement(), sql),
                RemoteProtocol.PrepareResult.class);
        EngineApi.PreparedStatementDescription description = result.description();
        return new PreparedHandle(description.statementId(), description.parameterCount(),
                description.parameterDescriptions(), description.resultColumns());
    }

    @Override
    public synchronized EngineApi.BatchResult executePrepared(long statementId, List<String> parameterLiterals,
                                                              long executionId, long timeoutMillis) throws SQLException {
        RemoteProtocol.ExecuteResult result = exchange(new RemoteProtocol.ExecutePreparedRequest(
                        executionId, statementId, parameterLiterals, timeoutMillis),
                RemoteProtocol.ExecuteResult.class);
        return result.result();
    }

    @Override
    public synchronized void closePrepared(long statementId) throws SQLException {
        exchange(new RemoteProtocol.ClosePreparedRequest(nextRequestId.getAndIncrement(), statementId),
                RemoteProtocol.ClosePreparedResult.class);
    }

    @Override
    public boolean cancel(long executionId) throws SQLException {
        ensureOpen();
        try (Socket cancelSocket = new Socket()) {
            cancelSocket.connect(new InetSocketAddress(url.host(), url.port()), url.connectTimeoutMillis());
            cancelSocket.setSoTimeout(Math.max(url.socketTimeoutMillis(), 1_000));
            cancelSocket.setTcpNoDelay(true);
            try (BufferedInputStream cancelInput = new BufferedInputStream(cancelSocket.getInputStream());
                 BufferedOutputStream cancelOutput = new BufferedOutputStream(cancelSocket.getOutputStream())) {
                RemoteProtocol.write(cancelOutput, new RemoteProtocol.ClientHello(
                        url.clientName(), RemoteProtocol.VERSION, url.user(), url.password()), maxFrameBytes);
                RemoteProtocol.Message hello = RemoteProtocol.read(cancelInput, maxFrameBytes);
                if (hello instanceof RemoteProtocol.Failure failure) {
                    throw DaisyBaseExceptionFactory.fromFailure(failure);
                }
                if (!(hello instanceof RemoteProtocol.ServerHello)) {
                    throw new SQLException("Unexpected cancel handshake response: "
                            + (hello == null ? "null" : hello.getClass().getSimpleName()));
                }
                RemoteProtocol.CancelResult result = exchange(cancelInput, cancelOutput,
                        new RemoteProtocol.CancelRequest(1L, sessionToken, executionId),
                        RemoteProtocol.CancelResult.class);
                return result.cancelled();
            }
        } catch (IOException exception) {
            throw DaisyBaseExceptionFactory.fromException(exception);
        }
    }

    @Override
    public synchronized void begin(Common.IsolationLevel isolationLevel) throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "BEGIN", isolationLevel.name().replace('_', ' ')),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized void commit() throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "COMMIT", ""),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized void rollback() throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "ROLLBACK", ""),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized void savepoint(String name) throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "SAVEPOINT", name),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized void rollbackToSavepoint(String name) throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "ROLLBACK_TO_SAVEPOINT", name),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized boolean active() throws SQLException {
        return exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "ACTIVE", ""),
                RemoteProtocol.TransactionResult.class).active();
    }

    @Override
    public synchronized void xaPrepare(EngineApi.XidDescriptor xid) throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "XA_PREPARE", encodeXid(xid)),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized void xaCommit(EngineApi.XidDescriptor xid, boolean onePhase) throws SQLException {
        String argument = (onePhase ? "1" : "0") + "|" + encodeXid(xid);
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "XA_COMMIT", argument),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized void xaRollback(EngineApi.XidDescriptor xid) throws SQLException {
        exchange(new RemoteProtocol.TransactionRequest(nextRequestId.getAndIncrement(), "XA_ROLLBACK", encodeXid(xid)),
                RemoteProtocol.TransactionResult.class);
    }

    @Override
    public synchronized List<EngineApi.XidDescriptor> xaRecover() throws SQLException {
        Common.TupleBatch batch = metadata(EngineIntrospection.MetadataQuery.XA_RECOVER, List.of());
        return batch.rows().stream()
                .map(row -> new EngineApi.XidDescriptor(
                        row.get(0).asInt(),
                        row.get(1).asBytes(),
                        row.get(2).asBytes()))
                .toList();
    }

    @Override
    public synchronized Common.TupleBatch metadata(EngineIntrospection.MetadataQuery query, List<String> arguments) throws SQLException {
        return exchange(new RemoteProtocol.MetadataRequest(nextRequestId.getAndIncrement(), query.name(), arguments),
                RemoteProtocol.MetadataResult.class).batch();
    }

    @Override
    public synchronized void ping() throws SQLException {
        metadata(EngineIntrospection.MetadataQuery.SCHEMAS, List.of("%"));
    }

    @Override
    public synchronized void setNetworkTimeoutMillis(int milliseconds) throws SQLException {
        ensureOpen();
        try {
            socket.setSoTimeout(milliseconds);
        } catch (IOException exception) {
            throw DaisyBaseExceptionFactory.fromException(exception);
        }
    }

    @Override
    public synchronized int getNetworkTimeoutMillis() {
        try {
            return socket.getSoTimeout();
        } catch (IOException exception) {
            return 0;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            try {
                RemoteProtocol.write(output, new RemoteProtocol.CloseRequest("jdbc close"), maxFrameBytes);
                RemoteProtocol.read(input, maxFrameBytes);
            } catch (Exception ignored) {
            }
            socket.close();
        } catch (IOException exception) {
            throw DaisyBaseExceptionFactory.fromException(exception);
        }
    }

    private <T extends RemoteProtocol.Message> T exchange(RemoteProtocol.Message request, Class<T> responseType) throws SQLException {
        ensureOpen();
        try {
            RemoteProtocol.write(output, request, maxFrameBytes);
            RemoteProtocol.Message response = RemoteProtocol.read(input, maxFrameBytes);
            return castResponse(response, responseType);
        } catch (IOException exception) {
            throw DaisyBaseExceptionFactory.fromException(exception);
        }
    }

    private <T extends RemoteProtocol.Message> T exchange(BufferedInputStream responseInput,
                                                          BufferedOutputStream requestOutput,
                                                          RemoteProtocol.Message request,
                                                          Class<T> responseType) throws SQLException {
        try {
            RemoteProtocol.write(requestOutput, request, maxFrameBytes);
            RemoteProtocol.Message response = RemoteProtocol.read(responseInput, maxFrameBytes);
            return castResponse(response, responseType);
        } catch (IOException exception) {
            throw DaisyBaseExceptionFactory.fromException(exception);
        }
    }

    private <T extends RemoteProtocol.Message> T castResponse(RemoteProtocol.Message response,
                                                              Class<T> responseType) throws SQLException {
        if (response instanceof RemoteProtocol.Failure failure) {
            throw DaisyBaseExceptionFactory.fromFailure(failure);
        }
        if (!responseType.isInstance(response)) {
            throw new SQLException("Unexpected response type: " + (response == null ? "null" : response.getClass().getSimpleName()));
        }
        return responseType.cast(response);
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Transport is closed");
        }
    }

    private static String encodeXid(EngineApi.XidDescriptor xid) {
        return xid.formatId() + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(xid.globalId()) + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(xid.branchId());
    }
}
