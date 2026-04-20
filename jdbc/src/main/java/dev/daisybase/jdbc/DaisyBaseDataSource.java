package dev.daisybase.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Minimal {@link DataSource} implementation for DaisyBase.
 *
 * <p>This wrapper delegates connection creation to {@link DriverManager} so it can be
 * used in lightweight applications and tests without an additional pooling layer.</p>
 */
public final class DaisyBaseDataSource implements DataSource {
    private String url;
    private String user;
    private String password;
    private PrintWriter logWriter;
    private int loginTimeout;

    /**
     * Creates an unconfigured data source.
     *
     * <p>Set the URL, and optionally user/password defaults, before requesting connections.</p>
     */
    public DaisyBaseDataSource() {
    }

    /**
     * Sets the JDBC URL used for subsequent connections.
     *
     * @param url embedded or remote DaisyBase JDBC URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the configured JDBC URL.
     *
     * @return configured DaisyBase URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the default user name passed to {@link #getConnection()}.
     *
     * @param user driver-level user property
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Returns the default user name passed to {@link #getConnection()}.
     *
     * @return configured user name or {@code null}
     */
    public String getUser() {
        return user;
    }

    /**
     * Sets the default password passed to {@link #getConnection()}.
     *
     * @param password driver-level password property
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the configured default password.
     *
     * @return configured password or {@code null}
     */
    public String getPassword() {
        return password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return DriverManager.getConnection(url, properties);
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("dev.daisybase.jdbc");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
