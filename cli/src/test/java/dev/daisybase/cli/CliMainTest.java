package dev.daisybase.cli;

import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliMainTest {
    @TempDir
    Path tempDir;

    @Test
    void loadCommandImportsRowsAndPrintsSummary() throws Exception {
        Path home = tempDir.resolve("cli-home");
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(home);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE cli_people (id INT PRIMARY KEY, name TEXT NOT NULL);");
        }
        Path csv = tempDir.resolve("cli-people.csv");
        Files.writeString(csv, String.join("\n",
                "id,name",
                "1,Ada",
                "2,Grace"
        ), StandardCharsets.UTF_8);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            CliMain.main(new String[]{
                    "load",
                    "--home", home.toString(),
                    "--file", csv.toString(),
                    "--sql", "INSERT INTO cli_people (id, name) VALUES (?, ?);",
                    "--header"
            });
        } finally {
            System.setOut(originalOut);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("rowsLoaded=2"));
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(home);
             EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult rows = session.execute(
                    "SELECT name FROM cli_people ORDER BY id;").statements().getFirst();
            assertEquals(2, rows.batch().rows().size());
            assertEquals("Ada", rows.batch().rows().getFirst().values().getFirst().asText());
            assertEquals("Grace", rows.batch().rows().get(1).values().getFirst().asText());
        }
    }
}
