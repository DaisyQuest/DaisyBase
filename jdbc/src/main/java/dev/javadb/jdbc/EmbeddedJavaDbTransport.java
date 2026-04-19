package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineApi;
import dev.javadb.engine.EngineIntrospection;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class EmbeddedJavaDbTransport implements JavaDbTransport {
    private final EmbeddedEngineRegistry.BorrowedSession borrowedSession;
    private final AtomicLong nextExecutionId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Common.ExecutionControl> executionControls = new ConcurrentHashMap<>();
    private int networkTimeoutMillis;
    private boolean closed;

    EmbeddedJavaDbTransport(JavaDbUrl url) throws SQLException {
        this.borrowedSession = EmbeddedEngineRegistry.acquire(url);
        this.networkTimeoutMillis = url.socketTimeoutMillis();
    }

    @Override
    public long nextExecutionId() {
        return nextExecutionId.getAndIncrement();
    }

    @Override
    public EngineApi.BatchResult execute(String sql, long executionId, long timeoutMillis) throws SQLException {
        ensureOpen();
        Common.ExecutionControl control = timeoutMillis > 0
                ? Common.ExecutionControl.timeoutMillis(timeoutMillis)
                : Common.ExecutionControl.none();
        executionControls.put(executionId, control);
        try {
            return borrowedSession.session().execute(sql, control);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        } finally {
            executionControls.remove(executionId);
        }
    }

    @Override
    public PreparedHandle prepare(String sql) throws SQLException {
        ensureOpen();
        try {
            EngineApi.PreparedStatementDescription description = borrowedSession.session().prepare(sql);
            return new PreparedHandle(description.statementId(), description.parameterCount(),
                    description.parameterDescriptions(), description.resultColumns());
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public EngineApi.BatchResult executePrepared(long statementId, List<String> parameterLiterals,
                                                 long executionId, long timeoutMillis) throws SQLException {
        ensureOpen();
        Common.ExecutionControl control = timeoutMillis > 0
                ? Common.ExecutionControl.timeoutMillis(timeoutMillis)
                : Common.ExecutionControl.none();
        executionControls.put(executionId, control);
        try {
            return borrowedSession.session().executePrepared(statementId, parameterLiterals, control);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        } finally {
            executionControls.remove(executionId);
        }
    }

    @Override
    public void closePrepared(long statementId) throws SQLException {
        ensureOpen();
        try {
            borrowedSession.session().closePrepared(statementId);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public boolean cancel(long executionId) throws SQLException {
        ensureOpen();
        Common.ExecutionControl control = executionControls.get(executionId);
        if (control == null) {
            return false;
        }
        control.cancel("Query was cancelled by JDBC client");
        return true;
    }

    @Override
    public void begin(Common.IsolationLevel isolationLevel) throws SQLException {
        ensureOpen();
        try {
            borrowedSession.session().transaction().begin(isolationLevel);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public void commit() throws SQLException {
        ensureOpen();
        try {
            borrowedSession.session().transaction().commit();
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public void rollback() throws SQLException {
        ensureOpen();
        try {
            borrowedSession.session().transaction().rollback();
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public void savepoint(String name) throws SQLException {
        ensureOpen();
        try {
            borrowedSession.session().transaction().savepoint(name);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public void rollbackToSavepoint(String name) throws SQLException {
        ensureOpen();
        try {
            borrowedSession.session().transaction().rollbackToSavepoint(name);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public boolean active() throws SQLException {
        ensureOpen();
        try {
            return borrowedSession.session().transaction().active();
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public Common.TupleBatch metadata(EngineIntrospection.MetadataQuery query, List<String> arguments) throws SQLException {
        ensureOpen();
        try {
            return EngineIntrospection.query(borrowedSession.engine(), query, arguments);
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
    }

    @Override
    public void ping() throws SQLException {
        ensureOpen();
    }

    @Override
    public void setNetworkTimeoutMillis(int milliseconds) throws SQLException {
        ensureOpen();
        this.networkTimeoutMillis = milliseconds;
    }

    @Override
    public int getNetworkTimeoutMillis() {
        return networkTimeoutMillis;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        EmbeddedEngineRegistry.release(borrowedSession);
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Transport is closed");
        }
    }
}
