package dev.javadb.server;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineIntrospection;
import dev.javadb.engine.EngineApi;
import dev.javadb.engine.RemoteProtocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class DatabaseProtocolServer implements AutoCloseable {
    private static final String SERVER_NAME = "javadb-server";

    private final EngineApi.DatabaseEngine engine;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final int maxFrameBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> acceptFailure = new AtomicReference<>();
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Authentication authentication;

    private DatabaseProtocolServer(EngineApi.DatabaseEngine engine, ServerSocket serverSocket, int maxFrameBytes,
                                   Authentication authentication) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.serverSocket = Objects.requireNonNull(serverSocket, "serverSocket");
        this.maxFrameBytes = maxFrameBytes;
        this.authentication = authentication == null ? Authentication.disabled() : authentication;
        this.acceptThread = Thread.ofPlatform().name("javadb-server-accept").start(this::acceptLoop);
    }

    static DatabaseProtocolServer start(EngineApi.DatabaseEngine engine, int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        return new DatabaseProtocolServer(engine, serverSocket, RemoteProtocol.DEFAULT_MAX_FRAME_BYTES,
                Authentication.disabled());
    }

    static DatabaseProtocolServer start(EngineApi.DatabaseEngine engine, int port,
                                        String user, String password) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        return new DatabaseProtocolServer(engine, serverSocket, RemoteProtocol.DEFAULT_MAX_FRAME_BYTES,
                Authentication.required(user, password));
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void await() throws Exception {
        acceptThread.join();
        Throwable failure = acceptFailure.get();
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new IllegalStateException("Server accept loop failed", failure);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        serverSocket.close();
        try {
            acceptThread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread.ofVirtual().name("javadb-server-client").start(() -> handleClient(socket));
            } catch (SocketException socketException) {
                if (closed.get()) {
                    return;
                }
                acceptFailure.compareAndSet(null, socketException);
                return;
            } catch (Throwable throwable) {
                acceptFailure.compareAndSet(null, throwable);
                return;
            }
        }
    }

    private void handleClient(Socket socket) {
        String sessionToken = UUID.randomUUID().toString();
        SessionState sessionState = new SessionState(engine.openSession());
        sessions.put(sessionToken, sessionState);
        try (socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            if (!performHandshake(input, output, sessionToken)) {
                return;
            }
            RemoteProtocol.Message message;
            while ((message = RemoteProtocol.read(input, maxFrameBytes)) != null) {
                switch (message) {
                    case RemoteProtocol.ExecuteRequest request -> handleExecute(sessionState, output, request);
                    case RemoteProtocol.PrepareRequest request -> handlePrepare(sessionState, output, request);
                    case RemoteProtocol.ExecutePreparedRequest request -> handleExecutePrepared(sessionState, output, request);
                    case RemoteProtocol.ClosePreparedRequest request -> handleClosePrepared(sessionState, output, request);
                    case RemoteProtocol.CancelRequest request -> handleCancel(output, request);
                    case RemoteProtocol.TransactionRequest request -> handleTransaction(sessionState.session(), output, request);
                    case RemoteProtocol.MetadataRequest request -> handleMetadata(output, request);
                    case RemoteProtocol.CloseRequest request -> {
                        RemoteProtocol.write(output, new RemoteProtocol.Goodbye(closeMessage(request.reason())), maxFrameBytes);
                        return;
                    }
                    default -> {
                        writeFailure(output, RemoteProtocol.Failure.protocolError(
                                "Unexpected message type after handshake: " + message.getClass().getSimpleName(), true));
                        return;
                    }
                }
            }
        } catch (RemoteProtocol.ProtocolException protocolException) {
            logClientFailure(protocolException);
        } catch (IOException ignored) {
        } catch (Exception exception) {
            logClientFailure(exception);
        } finally {
            sessions.remove(sessionToken);
            sessionState.close();
        }
    }

    private boolean performHandshake(BufferedInputStream input, BufferedOutputStream output, String sessionToken) throws IOException {
        RemoteProtocol.Message message = RemoteProtocol.read(input, maxFrameBytes);
        if (message == null) {
            return false;
        }
        if (!(message instanceof RemoteProtocol.ClientHello hello)) {
            writeFailure(output, RemoteProtocol.Failure.protocolError("Expected CLIENT_HELLO as the first frame", true));
            return false;
        }
        if (hello.protocolVersion() != RemoteProtocol.VERSION) {
            writeFailure(output, RemoteProtocol.Failure.protocolError(
                    "Unsupported protocol version: " + hello.protocolVersion(), true));
            return false;
        }
        if (!authentication.authenticate(hello.user(), hello.password())) {
            writeFailure(output, new RemoteProtocol.Failure(-1, "AUTHENTICATION_FAILED",
                    "Authentication failed", Common.SourceSpan.NONE, true));
            return false;
        }
        RemoteProtocol.write(output, new RemoteProtocol.ServerHello(
                SERVER_NAME, RemoteProtocol.VERSION, maxFrameBytes, sessionToken), maxFrameBytes);
        return true;
    }

    private void handleExecute(SessionState sessionState, BufferedOutputStream output,
                               RemoteProtocol.ExecuteRequest request) throws IOException {
        Common.ExecutionControl control = request.timeoutMillis() > 0
                ? Common.ExecutionControl.timeoutMillis(request.timeoutMillis())
                : Common.ExecutionControl.none();
        sessionState.executions().put(request.requestId(), control);
        try {
            EngineApi.BatchResult result = sessionState.session().execute(request.sql(), control);
            RemoteProtocol.write(output, new RemoteProtocol.ExecuteResult(request.requestId(), result), maxFrameBytes);
        } catch (RemoteProtocol.ProtocolException protocolException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), "RESULT_TOO_LARGE",
                    protocolException.getMessage(), Common.SourceSpan.NONE, false));
        } catch (Common.DatabaseException databaseException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), databaseException.code().name(),
                    databaseException.getMessage(), databaseException.span(), false));
        } catch (RuntimeException runtimeException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), Common.ErrorCode.INTERNAL_ERROR.name(),
                    runtimeException.getMessage(), Common.SourceSpan.NONE, true));
            throw runtimeException;
        } finally {
            sessionState.executions().remove(request.requestId());
        }
    }

    private void handlePrepare(SessionState sessionState, BufferedOutputStream output,
                               RemoteProtocol.PrepareRequest request) throws IOException {
        try {
            EngineApi.PreparedStatementDescription description = sessionState.session().prepare(request.sql());
            RemoteProtocol.write(output, new RemoteProtocol.PrepareResult(request.requestId(), description), maxFrameBytes);
        } catch (Common.DatabaseException databaseException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), databaseException.code().name(),
                    databaseException.getMessage(), databaseException.span(), false));
        }
    }

    private void handleExecutePrepared(SessionState sessionState, BufferedOutputStream output,
                                       RemoteProtocol.ExecutePreparedRequest request) throws IOException {
        Common.ExecutionControl control = request.timeoutMillis() > 0
                ? Common.ExecutionControl.timeoutMillis(request.timeoutMillis())
                : Common.ExecutionControl.none();
        sessionState.executions().put(request.requestId(), control);
        try {
            EngineApi.BatchResult result = sessionState.session().executePrepared(
                    request.statementId(), request.parameterLiterals(), control);
            RemoteProtocol.write(output, new RemoteProtocol.ExecuteResult(request.requestId(), result), maxFrameBytes);
        } catch (Common.DatabaseException databaseException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), databaseException.code().name(),
                    databaseException.getMessage(), databaseException.span(), false));
        } finally {
            sessionState.executions().remove(request.requestId());
        }
    }

    private void handleClosePrepared(SessionState sessionState, BufferedOutputStream output,
                                     RemoteProtocol.ClosePreparedRequest request) throws IOException {
        try {
            sessionState.session().closePrepared(request.statementId());
            RemoteProtocol.write(output, new RemoteProtocol.ClosePreparedResult(request.requestId()), maxFrameBytes);
        } catch (Common.DatabaseException databaseException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), databaseException.code().name(),
                    databaseException.getMessage(), databaseException.span(), false));
        }
    }

    private void handleCancel(BufferedOutputStream output, RemoteProtocol.CancelRequest request) throws IOException {
        SessionState sessionState = sessions.get(request.sessionToken());
        boolean cancelled = false;
        if (sessionState != null) {
            Common.ExecutionControl control = sessionState.executions().get(request.executionId());
            if (control != null) {
                control.cancel("Query was cancelled by remote client");
                cancelled = true;
            }
        }
        RemoteProtocol.write(output, new RemoteProtocol.CancelResult(request.requestId(), cancelled), maxFrameBytes);
    }

    private void handleTransaction(EngineApi.Session session, BufferedOutputStream output,
                                   RemoteProtocol.TransactionRequest request) throws IOException {
        try {
            EngineApi.TransactionHandle transaction = session.transaction();
            switch (request.operation()) {
                case "BEGIN" -> transaction.begin(request.argument().isBlank()
                        ? Common.IsolationLevel.READ_COMMITTED
                        : Common.IsolationLevel.fromSqlWords(List.of(request.argument().trim().split("\\s+"))));
                case "COMMIT" -> transaction.commit();
                case "ROLLBACK" -> transaction.rollback();
                case "SAVEPOINT" -> transaction.savepoint(request.argument());
                case "ROLLBACK_TO_SAVEPOINT" -> transaction.rollbackToSavepoint(request.argument());
                case "ACTIVE" -> {
                }
                default -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Unsupported transaction operation " + request.operation());
            }
            RemoteProtocol.write(output, new RemoteProtocol.TransactionResult(request.requestId(), transaction.active()), maxFrameBytes);
        } catch (Common.DatabaseException databaseException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), databaseException.code().name(),
                    databaseException.getMessage(), databaseException.span(), false));
        }
    }

    private void handleMetadata(BufferedOutputStream output, RemoteProtocol.MetadataRequest request) throws IOException {
        try {
            Common.TupleBatch batch = EngineIntrospection.query(engine,
                    EngineIntrospection.MetadataQuery.valueOf(request.operation()),
                    request.arguments());
            RemoteProtocol.write(output, new RemoteProtocol.MetadataResult(request.requestId(), batch), maxFrameBytes);
        } catch (IllegalArgumentException illegalArgumentException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), Common.ErrorCode.UNSUPPORTED_FEATURE.name(),
                    "Unsupported metadata operation " + request.operation(), Common.SourceSpan.NONE, false));
        } catch (Common.DatabaseException databaseException) {
            writeFailure(output, new RemoteProtocol.Failure(request.requestId(), databaseException.code().name(),
                    databaseException.getMessage(), databaseException.span(), false));
        }
    }

    private void writeFailure(BufferedOutputStream output, RemoteProtocol.Failure failure) throws IOException {
        RemoteProtocol.write(output, failure, maxFrameBytes);
    }

    private void logClientFailure(Exception exception) {
        System.err.println("Client session failed: " + exception.getMessage());
    }

    private static String closeMessage(String reason) {
        return reason == null || reason.isBlank() ? "bye" : reason.trim();
    }

    private record Authentication(String user, String password) {
        static Authentication disabled() {
            return new Authentication("", "");
        }

        static Authentication required(String user, String password) {
            String normalizedUser = user == null ? "" : user.strip();
            if (normalizedUser.isEmpty()) {
                return disabled();
            }
            return new Authentication(normalizedUser, password == null ? "" : password);
        }

        boolean authenticate(String candidateUser, String candidatePassword) {
            if (user.isEmpty()) {
                return true;
            }
            return user.equals(candidateUser == null ? "" : candidateUser)
                    && password.equals(candidatePassword == null ? "" : candidatePassword);
        }
    }

    private record SessionState(EngineApi.Session session, Map<Long, Common.ExecutionControl> executions) {
        private SessionState(EngineApi.Session session) {
            this(session, new ConcurrentHashMap<>());
        }

        private void close() {
            executions.values().forEach(control -> control.cancel("Session closed"));
            session.close();
        }
    }
}
