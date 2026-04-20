package dev.daisybase.cli;

import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.engine.LaunchConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

public final class CliMain {
    private CliMain() {
    }

    public static void main(String[] args) throws Exception {
        if (BulkLoadUtility.isDirectCommand(args)) {
            runBulkLoadCommand(args);
            return;
        }
        LaunchConfig config = resolveConfig(args);
        applyReferenceParserSettings(config);
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(config.databaseConfig());
             EngineApi.Session session = engine.openSession();
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            StringBuilder buffer = new StringBuilder();
            while (true) {
                System.out.print(buffer.isEmpty() ? "daisybase> " : "....> ");
                String line = reader.readLine();
                if (line == null || line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    break;
                }
                buffer.append(line).append('\n');
                if (!line.trim().endsWith(";")) {
                    continue;
                }
                EngineApi.BatchResult result = session.execute(buffer.toString());
                buffer.setLength(0);
                for (EngineApi.StatementResult statement : result.statements()) {
                    System.out.println(statement.commandTag() + " " + statement.updateCount());
                    if (!statement.explainPlan().isBlank()) {
                        System.out.println("plan: " + statement.explainPlan());
                    }
                    if (!statement.batch().rows().isEmpty()) {
                        System.out.println(statement.batch().columns());
                        statement.batch().rows().forEach(row -> System.out.println(row.values()));
                    }
                }
            }
        }
    }

    private static void runBulkLoadCommand(String[] args) throws Exception {
        BulkLoadUtility.LoadCommand command = BulkLoadUtility.parseCommand(args);
        LaunchConfig config = resolveConfig(command);
        applyReferenceParserSettings(config);
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(config.databaseConfig());
             EngineApi.Session session = engine.openSession()) {
            BulkLoadUtility.BulkLoadResult result = BulkLoadUtility.execute(session, command.request());
            System.out.println(BulkLoadUtility.renderSummary(result));
        }
    }

    private static LaunchConfig resolveConfig(BulkLoadUtility.LoadCommand command) {
        if (command.configPath() != null) {
            return LaunchConfig.load(command.configPath());
        }
        return LaunchConfig.defaults(command.home() == null ? Path.of("./db-home") : command.home());
    }

    static LaunchConfig resolveConfig(String[] args) {
        if (args.length >= 2 && "--config".equalsIgnoreCase(args[0])) {
            return LaunchConfig.load(Path.of(args[1]));
        }
        Path home = args.length > 0 ? Path.of(args[0]) : Path.of("./db-home");
        return LaunchConfig.defaults(home);
    }

    static void applyReferenceParserSettings(LaunchConfig config) {
        System.setProperty("daisybase.sql.referenceParser.mode", config.referenceParserMode());
        System.setProperty("javadb.sql.referenceParser.mode", config.referenceParserMode());
        if (config.referenceParserHome() != null) {
            System.setProperty("daisybase.sql.referenceParser.home", config.referenceParserHome().toString());
            System.setProperty("javadb.sql.referenceParser.home", config.referenceParserHome().toString());
        }
    }
}
