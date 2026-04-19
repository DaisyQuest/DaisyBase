package dev.javadb.testkit;

import dev.javadb.engine.EngineApi;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestKitTest {
    @Test
    void tempHomeCanReopenEnginesAgainstTheSameDirectory() {
        try (TestKit.TempHome tempHome = TestKit.tempHome()) {
            Path marker = tempHome.resolve("marker.txt");
            assertEquals(tempHome.path().resolve("marker.txt"), marker);

            try (EngineApi.DatabaseEngine engine = tempHome.openEngine();
                 EngineApi.Session session = engine.openSession()) {
                session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL);");
                session.execute("INSERT INTO users VALUES (1, 'Ada');");
            }

            try (EngineApi.DatabaseEngine engine = tempHome.openEngine();
                 EngineApi.Session session = engine.openSession()) {
                assertEquals("Ada", scalarText(session.execute("SELECT name FROM users WHERE id = 1;")));
            }
        }
    }

    @Test
    void tempEngineRestartPreservesHomeUntilManagedClose() {
        Path home;
        try (TestKit.TempEngine engine = TestKit.tempEngine()) {
            home = engine.home();
            assertTrue(Files.exists(home));

            try (EngineApi.Session session = engine.openSession()) {
                session.execute("CREATE TABLE items (id INT PRIMARY KEY, name TEXT NOT NULL);");
                session.execute("INSERT INTO items VALUES (1, 'one');");
            }

            engine.restart();

            try (EngineApi.Session session = engine.openSession()) {
                assertEquals(1L, scalarLong(session.execute("SELECT COUNT(*) AS total FROM items;")));
            }

            assertTrue(Files.exists(home));
        }

        assertFalse(Files.exists(home));
    }

    private static long scalarLong(EngineApi.BatchResult result) {
        return result.statements().getFirst().batch().rows().getFirst().get(0).asLong();
    }

    private static String scalarText(EngineApi.BatchResult result) {
        return result.statements().getFirst().batch().rows().getFirst().get(0).asText();
    }
}
