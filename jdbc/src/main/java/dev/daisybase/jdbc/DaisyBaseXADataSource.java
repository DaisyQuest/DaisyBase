package dev.daisybase.jdbc;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * XA-capable data source for DaisyBase.
 *
 * <p>This implementation provides durable prepare/recover/commit/rollback semantics for one
 * active XA branch per underlying DaisyBase connection.</p>
 */
public final class DaisyBaseXADataSource implements XADataSource {
    private final DaisyBaseDataSource delegate = new DaisyBaseDataSource();

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
        return new DaisyBaseXAConnection((DaisyBaseConnection) delegate.getConnection(user, password));
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
