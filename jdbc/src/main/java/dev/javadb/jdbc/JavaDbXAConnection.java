package dev.javadb.jdbc;

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.PooledConnection;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class JavaDbXAConnection implements XAConnection {
    private final JavaDbConnection connection;
    private final JavaDbXAResource resource;
    private final List<ConnectionEventListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<StatementEventListener> statementListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    JavaDbXAConnection(JavaDbConnection connection) {
        this.connection = connection;
        this.resource = new JavaDbXAResource(connection);
    }

    @Override
    public Connection getConnection() throws SQLException {
        ensureOpen();
        return connection;
    }

    @Override
    public XAResource getXAResource() throws SQLException {
        ensureOpen();
        return resource;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        SQLException failure = null;
        try {
            connection.close();
        } catch (SQLException exception) {
            failure = exception;
            fireConnectionError(exception);
        }
        fireConnectionClosed();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        connectionListeners.remove(listener);
    }

    @Override
    public void addStatementEventListener(StatementEventListener listener) {
        if (listener != null) {
            statementListeners.add(listener);
        }
    }

    @Override
    public void removeStatementEventListener(StatementEventListener listener) {
        statementListeners.remove(listener);
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("XAConnection is closed");
        }
    }

    private void fireConnectionClosed() {
        ConnectionEvent event = new ConnectionEvent((PooledConnection) this);
        for (ConnectionEventListener listener : connectionListeners) {
            listener.connectionClosed(event);
        }
    }

    private void fireConnectionError(SQLException exception) {
        ConnectionEvent event = new ConnectionEvent((PooledConnection) this, exception);
        for (ConnectionEventListener listener : connectionListeners) {
            listener.connectionErrorOccurred(event);
        }
    }
}
