package dev.javadb.jdbc;

import dev.daisybase.jdbc.DaisyBaseDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Legacy compatibility wrapper for the pre-rebrand data source class name.
 */
public final class JavaDbDataSource implements DataSource {
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
    public Connection getConnection() throws SQLException {
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return delegate.getConnection(user, password);
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

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
