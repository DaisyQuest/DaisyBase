package dev.javadb.jdbc;

import dev.javadb.engine.RemoteProtocol;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

final class JavaDbUrl {
    enum Mode {
        EMBEDDED,
        REMOTE
    }

    private static final String PREFIX = "jdbc:javadb:";
    private static final String EMBEDDED_PREFIX = PREFIX + "embedded:";
    private static final String REMOTE_PREFIX = PREFIX + "remote://";

    private final Mode mode;
    private final String url;
    private final Path databaseHome;
    private final String host;
    private final int port;
    private final Properties properties;

    private JavaDbUrl(Mode mode, String url, Path databaseHome, String host, int port, Properties properties) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.url = Objects.requireNonNull(url, "url");
        this.databaseHome = databaseHome;
        this.host = host;
        this.port = port;
        this.properties = properties;
    }

    static boolean accepts(String url) {
        return url != null && (url.regionMatches(true, 0, EMBEDDED_PREFIX, 0, EMBEDDED_PREFIX.length())
                || url.regionMatches(true, 0, REMOTE_PREFIX, 0, REMOTE_PREFIX.length()));
    }

    static JavaDbUrl parse(String url, Properties incoming) {
        if (!accepts(url)) {
            throw new IllegalArgumentException("Unsupported JDBC URL: " + url);
        }
        Properties properties = new Properties();
        if (incoming != null) {
            properties.putAll(incoming);
        }
        if (url.regionMatches(true, 0, EMBEDDED_PREFIX, 0, EMBEDDED_PREFIX.length())) {
            String homeText = url.substring(EMBEDDED_PREFIX.length()).trim();
            if (homeText.isEmpty()) {
                throw new IllegalArgumentException("Embedded JDBC URL requires a database home path");
            }
            return new JavaDbUrl(Mode.EMBEDDED, url, Path.of(homeText).toAbsolutePath().normalize(), null, -1, properties);
        }
        String remoteText = url.substring(PREFIX.length());
        java.net.URI uri = java.net.URI.create(remoteText);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Remote JDBC URL requires a host");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 15432;
        return new JavaDbUrl(Mode.REMOTE, url, null, host, port, properties);
    }

    Mode mode() {
        return mode;
    }

    String url() {
        return url;
    }

    Path databaseHome() {
        return databaseHome;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    Properties properties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    String user() {
        return property("user", "");
    }

    String password() {
        return property("password", "");
    }

    String clientName() {
        return property("clientName", "javadb-jdbc");
    }

    int checkpointInterval() {
        return intProperty("checkpointInterval", 8);
    }

    boolean strictDurability() {
        return booleanProperty("strictDurability", true);
    }

    int socketTimeoutMillis() {
        return intProperty("socketTimeoutMillis", 5_000);
    }

    int connectTimeoutMillis() {
        return intProperty("connectTimeoutMillis", 5_000);
    }

    int maxFrameBytes() {
        return intProperty("maxFrameBytes", RemoteProtocol.DEFAULT_MAX_FRAME_BYTES);
    }

    private String property(String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private int intProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private boolean booleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim().toLowerCase(Locale.ROOT));
    }
}
