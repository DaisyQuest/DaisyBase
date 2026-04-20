package dev.daisybase.engine;

import dev.daisybase.common.Common;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public record LaunchConfig(EngineApi.DatabaseConfig databaseConfig, int serverPort, String referenceParserMode,
                           Path referenceParserHome, Path javaHome, String authUser, String authPassword) {
    public static final int DEFAULT_SERVER_PORT = 15432;

    public LaunchConfig {
        Objects.requireNonNull(databaseConfig, "databaseConfig");
        referenceParserMode = referenceParserMode == null || referenceParserMode.isBlank()
                ? "disabled"
                : referenceParserMode.toLowerCase(Locale.ROOT);
        serverPort = serverPort <= 0 ? DEFAULT_SERVER_PORT : serverPort;
        authUser = authUser == null ? "" : authUser.strip();
        authPassword = authPassword == null ? "" : authPassword;
    }

    public static LaunchConfig defaults(Path databaseHome) {
        return new LaunchConfig(EngineApi.DatabaseConfig.defaults(databaseHome), DEFAULT_SERVER_PORT,
                "disabled", null, null, "", "");
    }

    public static LaunchConfig load(Path configPath) {
        Objects.requireNonNull(configPath, "configPath");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to load launch config " + configPath, exception);
        }
        Path baseDir = configPath.toAbsolutePath().getParent();
        Path databaseHome = resolvePath(baseDir, properties.getProperty("database.home", "./db-home"));
        int checkpointInterval = parseInt(properties, "checkpoint.interval", 8);
        boolean strictDurability = Boolean.parseBoolean(properties.getProperty("strict.durability", "true"));
        int serverPort = parseInt(properties, "server.port", DEFAULT_SERVER_PORT);
        Path referenceParserHome = resolveOptionalPath(baseDir, properties.getProperty("reference.parser.home"));
        Path javaHome = resolveOptionalPath(baseDir, properties.getProperty("java.home"));
        String authUser = properties.getProperty("auth.user", "");
        String authPassword = properties.getProperty("auth.password", "");
        return new LaunchConfig(new EngineApi.DatabaseConfig(databaseHome, checkpointInterval, strictDurability),
                serverPort,
                properties.getProperty("reference.parser.mode", "disabled"),
                referenceParserHome,
                javaHome,
                authUser,
                authPassword);
    }

    private static int parseInt(Properties properties, String key, int defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Invalid integer value for " + key + ": " + raw, exception);
        }
    }

    private static Path resolveOptionalPath(Path baseDir, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return resolvePath(baseDir, text);
    }

    public static Path resolvePath(Path baseDir, String text) {
        Path path = Path.of(text);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }
}
