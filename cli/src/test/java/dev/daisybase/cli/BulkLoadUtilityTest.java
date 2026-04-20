package dev.daisybase.cli;

import dev.daisybase.engine.EngineApi;
import dev.daisybase.testkit.TestKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkLoadUtilityTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsCsvWithHeaderNullTokensAndQuotedFields() throws Exception {
        Path csv = tempDir.resolve("users.csv");
        Files.writeString(csv, String.join("\n",
                "id,name,active,joined_on",
                "1,Ada,true,2026-01-02",
                "2,\"Grace, Hopper\",false,NULL",
                "3,\"line one",
                "line two\",true,2026-03-04"
        ), StandardCharsets.UTF_8);
        try (TestKit.TempEngine engine = TestKit.tempEngine();
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, active BOOLEAN NOT NULL, joined_on DATE);");
            BulkLoadUtility.BulkLoadResult result = BulkLoadUtility.execute(session,
                    new BulkLoadUtility.BulkLoadRequest(csv,
                            "INSERT INTO users (id, name, active, joined_on) VALUES (?, ?, ?, ?);",
                            ',', '"', true, 2, Set.of("NULL"), BulkLoadUtility.LiteralMode.STRING,
                            StandardCharsets.UTF_8, 0, false));
            assertEquals(3, result.rowsRead());
            assertEquals(3, result.rowsLoaded());
            assertEquals(0, result.rowsFailed());
            EngineApi.StatementResult rows = session.execute(
                    "SELECT id, name, active, joined_on FROM users ORDER BY id;").statements().getFirst();
            assertEquals(3, rows.batch().rows().size());
            assertEquals("Grace, Hopper", rows.batch().rows().get(1).values().get(1).asText());
            assertEquals("line one\nline two", rows.batch().rows().get(2).values().get(1).asText());
            assertTrue(rows.batch().rows().get(1).values().get(3).isNull());
        }
    }

    @Test
    void continuesAfterRecoverableErrorsUpToConfiguredLimit() throws Exception {
        Path csv = tempDir.resolve("events.csv");
        Files.writeString(csv, String.join("\n",
                "id,note",
                "1,alpha",
                "1,duplicate",
                "2,bravo"
        ), StandardCharsets.UTF_8);
        try (TestKit.TempEngine engine = TestKit.tempEngine();
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE events (id INT PRIMARY KEY, note TEXT NOT NULL);");
            BulkLoadUtility.BulkLoadResult result = BulkLoadUtility.execute(session,
                    new BulkLoadUtility.BulkLoadRequest(csv,
                            "INSERT INTO events (id, note) VALUES (?, ?);",
                            ',', '"', true, 10, Set.of("NULL"), BulkLoadUtility.LiteralMode.STRING,
                            StandardCharsets.UTF_8, 1, false));
            assertEquals(3, result.rowsRead());
            assertEquals(2, result.rowsLoaded());
            assertEquals(1, result.rowsFailed());
            assertEquals(1, result.errors().size());
            assertEquals(2, result.errors().getFirst().rowNumber());
            assertEquals(3, result.errors().getFirst().lineNumber());
            assertEquals(2L, scalarLong(session, "SELECT COUNT(*) AS total FROM events;"));
        }
    }

    @Test
    void autoLiteralModeAllowsTypedTimestampLiterals() throws Exception {
        Path csv = tempDir.resolve("audit.csv");
        Files.writeString(csv, String.join("\n",
                "id,created_at,note",
                "1,TIMESTAMP '2026-04-20 01:02:03',loaded"
        ), StandardCharsets.UTF_8);
        try (TestKit.TempEngine engine = TestKit.tempEngine();
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE audit_log (id INT PRIMARY KEY, created_at TIMESTAMP NOT NULL, note TEXT NOT NULL);");
            BulkLoadUtility.BulkLoadResult result = BulkLoadUtility.execute(session,
                    new BulkLoadUtility.BulkLoadRequest(csv,
                            "INSERT INTO audit_log (id, created_at, note) VALUES (?, ?, ?);",
                            ',', '"', true, 10, Set.of("NULL"), BulkLoadUtility.LiteralMode.AUTO,
                            StandardCharsets.UTF_8, 0, false));
            assertEquals(1, result.rowsLoaded());
            EngineApi.StatementResult rows = session.execute(
                    "SELECT note FROM audit_log WHERE id = 1;").statements().getFirst();
            assertEquals("loaded", rows.batch().rows().getFirst().values().getFirst().asText());
        }
    }

    private static long scalarLong(EngineApi.Session session, String sql) {
        return session.execute(sql).statements().getFirst().batch().rows().getFirst().values().getFirst().asLong();
    }
}
