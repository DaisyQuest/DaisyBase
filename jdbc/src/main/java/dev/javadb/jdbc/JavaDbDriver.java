package dev.javadb.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC 4.3 driver entrypoint for JavaDB.
 *
 * <p>The driver supports two URL families:
 * {@code jdbc:javadb:embedded:/path/to/home} for in-process access and
 * {@code jdbc:javadb:remote://host[:port]} for protocol-based access.</p>
 */
public final class JavaDbDriver implements Driver {
    /** Stable driver name reported through {@link java.sql.DatabaseMetaData}. */
    public static final String NAME = "JavaDB JDBC Driver";
    /** Major driver version exposed to JDBC clients. */
    public static final int MAJOR_VERSION = 1;
    /** Minor driver version exposed to JDBC clients. */
    public static final int MINOR_VERSION = 0;

    static {
        try {
            DriverManager.registerDriver(new JavaDbDriver());
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
    public JavaDbDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return new JavaDbConnection(JavaDbUrl.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return JavaDbUrl.accepts(url);
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
        return Logger.getLogger("dev.javadb.jdbc");
    }

    private DriverPropertyInfo property(String name, boolean required) {
        DriverPropertyInfo info = new DriverPropertyInfo(name, null);
        info.required = required;
        return info;
    }
}
