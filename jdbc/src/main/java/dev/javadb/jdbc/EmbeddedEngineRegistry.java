package dev.javadb.jdbc;

import dev.javadb.engine.EmbeddedDatabaseEngine;
import dev.javadb.engine.EngineApi;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class EmbeddedEngineRegistry {
    private static final Map<Path, Entry> ENTRIES = new LinkedHashMap<>();

    private EmbeddedEngineRegistry() {
    }

    static synchronized BorrowedSession acquire(JavaDbUrl url) throws SQLException {
        Path home = url.databaseHome();
        Entry entry = ENTRIES.get(home);
        if (entry == null) {
            EngineApi.DatabaseConfig config = new EngineApi.DatabaseConfig(home, url.checkpointInterval(), url.strictDurability());
            entry = new Entry(EmbeddedDatabaseEngine.open(config), config, 0);
            ENTRIES.put(home, entry);
        } else if (entry.config.checkpointInterval() != url.checkpointInterval()
                || entry.config.strictDurability() != url.strictDurability()) {
            throw new SQLException("Embedded engine already open with a different configuration for " + home);
        }
        entry.referenceCount++;
        return new BorrowedSession(home, entry.engine, entry.engine.openSession());
    }

    static synchronized void release(BorrowedSession borrowedSession) throws SQLException {
        try {
            borrowedSession.session.close();
        } catch (RuntimeException runtimeException) {
            throw JavaDbExceptionFactory.fromException(runtimeException);
        }
        Entry entry = ENTRIES.get(borrowedSession.home);
        if (entry == null) {
            return;
        }
        entry.referenceCount--;
        if (entry.referenceCount <= 0) {
            try {
                entry.engine.close();
            } catch (RuntimeException runtimeException) {
                throw JavaDbExceptionFactory.fromException(runtimeException);
            }
            ENTRIES.remove(borrowedSession.home);
        }
    }

    record BorrowedSession(Path home, EngineApi.DatabaseEngine engine, EngineApi.Session session) {
        BorrowedSession {
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(session, "session");
        }
    }

    private static final class Entry {
        private final EngineApi.DatabaseEngine engine;
        private final EngineApi.DatabaseConfig config;
        private int referenceCount;

        private Entry(EngineApi.DatabaseEngine engine, EngineApi.DatabaseConfig config, int referenceCount) {
            this.engine = engine;
            this.config = config;
            this.referenceCount = referenceCount;
        }
    }
}
