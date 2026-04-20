package dev.daisybase.jdbc;

import dev.daisybase.engine.RemoteProtocol;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

final class DaisyBaseUrl {
    enum Mode {
        EMBEDDED,
        REMOTE
    }

    private static final String PREFIX = "jdbc:daisybase:";
    private static final String LEGACY_PREFIX = "jdbc:javadb:";
    private static final String EMBEDDED_PREFIX = PREFIX + "embedded:";
    private static final String LEGACY_EMBEDDED_PREFIX = LEGACY_PREFIX + "embedded:";
    private static final String REMOTE_PREFIX = PREFIX + "remote://";
    private static final String LEGACY_REMOTE_PREFIX = LEGACY_PREFIX + "remote://";

    private final Mode mode;
    private final String url;
    private final Path databaseHome;
    private final String host;
    private final int port;
    private final Properties properties;

    private DaisyBaseUrl(Mode mode, String url, Path databaseHome, String host, int port, Properties properties) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.url = Objects.requireNonNull(url, "url");
        this.databaseHome = databaseHome;
        this.host = host;
        this.port = port;
        this.properties = properties;
    }

    static boolean accepts(String url) {
        return url != null && (matchesPrefix(url, EMBEDDED_PREFIX)
                || matchesPrefix(url, REMOTE_PREFIX)
                || matchesPrefix(url, LEGACY_EMBEDDED_PREFIX)
                || matchesPrefix(url, LEGACY_REMOTE_PREFIX));
    }

    static DaisyBaseUrl parse(String url, Properties incoming) {
        if (!accepts(url)) {
            throw new IllegalArgumentException("Unsupported JDBC URL: " + url);
        }
        Properties properties = new Properties();
        if (incoming != null) {
            properties.putAll(incoming);
        }
        if (matchesPrefix(url, EMBEDDED_PREFIX) || matchesPrefix(url, LEGACY_EMBEDDED_PREFIX)) {
            String homeText = stripPrefix(url, EMBEDDED_PREFIX, LEGACY_EMBEDDED_PREFIX).trim();
            if (homeText.isEmpty()) {
                throw new IllegalArgumentException("Embedded JDBC URL requires a database home path");
            }
            return new DaisyBaseUrl(Mode.EMBEDDED, url, Path.of(homeText).toAbsolutePath().normalize(), null, -1, properties);
        }
        String remoteText = stripPrefix(url, PREFIX, LEGACY_PREFIX);
        java.net.URI uri = java.net.URI.create(remoteText);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Remote JDBC URL requires a host");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 15432;
        return new DaisyBaseUrl(Mode.REMOTE, url, null, host, port, properties);
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
        return property("clientName", "daisybase-jdbc");
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

    private static boolean matchesPrefix(String url, String prefix) {
        return url.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String stripPrefix(String url, String primaryPrefix, String legacyPrefix) {
        if (matchesPrefix(url, primaryPrefix)) {
            return url.substring(primaryPrefix.length());
        }
        return url.substring(legacyPrefix.length());
    }
}
