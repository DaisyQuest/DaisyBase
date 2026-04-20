package dev.daisybase.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC 4.3 driver entrypoint for DaisyBase.
 *
 * <p>The driver supports two URL families:
 * {@code jdbc:daisybase:embedded:/path/to/home} for in-process access and
 * {@code jdbc:daisybase:remote://host[:port]} for protocol-based access.</p>
 */
public final class DaisyBaseDriver implements Driver {
    /** Stable driver name reported through {@link java.sql.DatabaseMetaData}. */
    public static final String NAME = "DaisyBase JDBC Driver";
    /** Major driver version exposed to JDBC clients. */
    public static final int MAJOR_VERSION = 1;
    /** Minor driver version exposed to JDBC clients. */
    public static final int MINOR_VERSION = 0;

    static {
        try {
            DriverManager.registerDriver(new DaisyBaseDriver());
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * Creates a new driver instance.
     *
     * <p>Applications normally do not instantiate the driver directly; loading the class is
     * sufficient because it self-registers with {@link DriverManager}.</p>
     */
    public DaisyBaseDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return new DaisyBaseConnection(DaisyBaseUrl.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return DaisyBaseUrl.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[]{
                property("user", false),
                property("password", false),
                property("clientName", false),
                property("checkpointInterval", false),
                property("strictDurability", false),
                property("socketTimeoutMillis", false),
                property("connectTimeoutMillis", false),
                property("maxFrameBytes", false)
        };
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("dev.daisybase.jdbc");
    }

    private DriverPropertyInfo property(String name, boolean required) {
        DriverPropertyInfo info = new DriverPropertyInfo(name, null);
        info.required = required;
        return info;
    }
}
