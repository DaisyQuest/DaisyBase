package dev.javadb.jdbc;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * XA-capable data source for JavaDB.
 *
 * <p>This implementation provides durable prepare/recover/commit/rollback semantics for one
 * active XA branch per underlying JavaDB connection.</p>
 */
public final class JavaDbXADataSource implements XADataSource {
    private final JavaDbDataSource delegate = new JavaDbDataSource();

    public void setUrl(String url) {
        delegate.setUrl(url);
    }

    public String getUrl() {
        return delegate.getUrl();
    }

    public void setUser(String user) {
        delegate.setUser(user);
    }

    public String getUser() {
        return delegate.getUser();
    }

    public void setPassword(String password) {
        delegate.setPassword(password);
    }

    public String getPassword() {
        return delegate.getPassword();
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        return getXAConnection(getUser(), getPassword());
    }

    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        return new JavaDbXAConnection((JavaDbConnection) delegate.getConnection(user, password));
    }

    @Override
    public PrintWriter getLogWriter() {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        return delegate.getParentLogger();
    }
}
