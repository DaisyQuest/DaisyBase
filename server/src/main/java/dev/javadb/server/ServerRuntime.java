package dev.javadb.server;

import dev.javadb.engine.EmbeddedDatabaseEngine;
import dev.javadb.engine.EngineApi;
import dev.javadb.engine.LaunchConfig;

import java.nio.file.Path;

public final class ServerRuntime {
    private ServerRuntime() {
    }

    public static void main(String[] args) throws Exception {
        LaunchConfig config = resolveConfig(args);
        applyReferenceParserSettings(config);
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(config.databaseConfig());
             DatabaseProtocolServer server = DatabaseProtocolServer.start(
                     engine, config.serverPort(), config.authUser(), config.authPassword())) {
            server.await();
        }
    }

    private static LaunchConfig resolveConfig(String[] args) {
        if (args.length >= 2 && "--config".equalsIgnoreCase(args[0])) {
            return LaunchConfig.load(Path.of(args[1]));
        }
        Path home = args.length > 0 ? Path.of(args[0]) : Path.of("./db-home");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : LaunchConfig.DEFAULT_SERVER_PORT;
        LaunchConfig defaults = LaunchConfig.defaults(home);
        return new LaunchConfig(defaults.databaseConfig(), port, defaults.referenceParserMode(),
                defaults.referenceParserHome(), defaults.javaHome(), defaults.authUser(), defaults.authPassword());
    }

    private static void applyReferenceParserSettings(LaunchConfig config) {
        System.setProperty("javadb.sql.referenceParser.mode", config.referenceParserMode());
        if (config.referenceParserHome() != null) {
            System.setProperty("javadb.sql.referenceParser.home", config.referenceParserHome().toString());
        }
    }
}
