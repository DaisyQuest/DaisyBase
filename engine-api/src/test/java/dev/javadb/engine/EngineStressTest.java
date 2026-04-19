package dev.javadb.engine;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineStressTest {
    private static final int MAX_ID = 72;

    @TempDir
    Path tempDir;

    @Test
    void randomizedTransactionalStressMatchesOracleAcrossCheckpointsAndRestarts() {
        Random random = new Random(4_220_426L);
        TreeMap<Integer, ExpectedRow> expected = new TreeMap<>();
        EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
        try {
            execute(engine, "CREATE TABLE kv (id INT PRIMARY KEY, payload TEXT NOT NULL, note TEXT, score INT NOT NULL CHECK (score >= 0));");
            for (int step = 1; step <= 180; step++) {
                int action = random.nextInt(100);
                if (action < 55) {
                    applyTransactionalWork(engine, random, expected, step);
                } else if (action < 90) {
                    applySingleMutation(engine, random, expected, step);
                } else {
                    assertDuplicateInsertFails(engine, random, expected, step);
                }
                if (step % 9 == 0) {
                    assertCountMatches(engine, expected.size());
                }
                if (step % 17 == 0) {
                    engine.checkpoint();
                }
                if (step % 29 == 0) {
                    engine.close();
                    engine = EmbeddedDatabaseEngine.open(tempDir);
                }
                assertEngineMatchesExpected(engine, expected);
            }

            engine.close();
            engine = EmbeddedDatabaseEngine.open(tempDir);
            assertEngineMatchesExpected(engine, expected);
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    @Test
    void batchedWorkloadSurvivesAggressiveCheckpointsAndRestarts() {
        Random random = new Random(91_774L);
        TreeMap<Integer, ExpectedRow> expected = new TreeMap<>();
        EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
        try {
            execute(engine, "CREATE TABLE kv_batch (id INT PRIMARY KEY, payload TEXT NOT NULL, note TEXT, score INT NOT NULL CHECK (score >= 0));");
            for (int round = 1; round <= 75; round++) {
                List<String> statements = new ArrayList<>();
                int statementCount = 1 + random.nextInt(3);
                for (int index = 0; index < statementCount; index++) {
                    statements.add(buildValidMutationSql("kv_batch", random, expected, round * 100 + index));
                }
                String batchSql = String.join(System.lineSeparator(), statements) + System.lineSeparator();

                try (EngineApi.Session session = engine.openSession()) {
                    if (round % 2 == 0) {
                        execute(session, "BEGIN;" + System.lineSeparator() + batchSql + "COMMIT;");
                    } else {
                        execute(session, batchSql);
                    }
                }

                if (round % 2 == 0) {
                    engine.checkpoint();
                }
                if (round % 5 == 0) {
                    engine.close();
                    engine = EmbeddedDatabaseEngine.open(tempDir);
                }
                assertEngineMatchesExpected(engine, "kv_batch", expected);
            }

            engine.close();
            engine = EmbeddedDatabaseEngine.open(tempDir);
            assertEngineMatchesExpected(engine, "kv_batch", expected);
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    private void applyTransactionalWork(EngineApi.DatabaseEngine engine, Random random,
                                        TreeMap<Integer, ExpectedRow> expected, int step) {
        try (EngineApi.Session session = engine.openSession()) {
            EngineApi.TransactionHandle transaction = session.transaction();
            transaction.begin(Common.IsolationLevel.READ_COMMITTED);
            TreeMap<Integer, ExpectedRow> staged = new TreeMap<>(expected);
            TreeMap<Integer, ExpectedRow> savepointState = null;
            boolean savepointCreated = false;
            int operations = 2 + random.nextInt(3);
            for (int index = 0; index < operations; index++) {
                if (!savepointCreated && index > 0 && random.nextBoolean()) {
                    transaction.savepoint("sp");
                    savepointState = new TreeMap<>(staged);
                    savepointCreated = true;
                }
                performValidMutation(session, random, staged, step * 10 + index);
                if (savepointCreated && random.nextInt(5) == 0) {
                    transaction.rollbackToSavepoint("sp");
                    staged = new TreeMap<>(savepointState);
                    savepointCreated = false;
                }
            }
            if (savepointCreated && random.nextInt(4) == 0) {
                transaction.rollbackToSavepoint("sp");
                staged = new TreeMap<>(savepointState);
            }
            if (random.nextInt(10) < 7) {
                transaction.commit();
                expected.clear();
                expected.putAll(staged);
            } else {
                transaction.rollback();
            }
        }
    }

    private void applySingleMutation(EngineApi.DatabaseEngine engine, Random random,
                                     TreeMap<Integer, ExpectedRow> expected, int step) {
        try (EngineApi.Session session = engine.openSession()) {
            performValidMutation(session, random, expected, step);
        }
    }

    private void performValidMutation(EngineApi.Session session, Random random,
                                      TreeMap<Integer, ExpectedRow> expected, int step) {
        execute(session, buildValidMutationSql("kv", random, expected, step));
    }

    private String buildValidMutationSql(String tableName, Random random,
                                         TreeMap<Integer, ExpectedRow> expected, int step) {
        int action = random.nextInt(100);
        if (expected.isEmpty() || action < 40) {
            int id = findMissingId(expected, random);
            if (id < 0) {
                return updateExistingSql(tableName, random, expected, step);
            } else {
                return insertRowSql(tableName, expected, id, step);
            }
        }
        if (action < 75) {
            return updateExistingSql(tableName, random, expected, step);
        }
        return deleteExistingSql(tableName, random, expected);
    }

    private void insertRow(EngineApi.Session session, TreeMap<Integer, ExpectedRow> expected, int id, int step) {
        execute(session, insertRowSql("kv", expected, id, step));
    }

    private String insertRowSql(String tableName, TreeMap<Integer, ExpectedRow> expected, int id, int step) {
        ExpectedRow row = rowFor(id, step);
        expected.put(id, row);
        return "INSERT INTO " + tableName + " VALUES (" + id + ", " + sqlLiteral(row.payload()) + ", "
                + sqlLiteralOrNull(row.note()) + ", " + row.score() + ");";
    }

    private void updateExisting(EngineApi.Session session, Random random, TreeMap<Integer, ExpectedRow> expected, int step) {
        execute(session, updateExistingSql("kv", random, expected, step));
    }

    private String updateExistingSql(String tableName, Random random, TreeMap<Integer, ExpectedRow> expected, int step) {
        int id = pickExistingId(expected, random);
        ExpectedRow row = rowFor(id, step + 10_000);
        expected.put(id, row);
        return "UPDATE " + tableName + " SET payload = " + sqlLiteral(row.payload()) + ", note = "
                + sqlLiteralOrNull(row.note()) + ", score = " + row.score() + " WHERE id = " + id + ";";
    }

    private void deleteExisting(EngineApi.Session session, Random random, TreeMap<Integer, ExpectedRow> expected) {
        execute(session, deleteExistingSql("kv", random, expected));
    }

    private String deleteExistingSql(String tableName, Random random, TreeMap<Integer, ExpectedRow> expected) {
        int id = pickExistingId(expected, random);
        expected.remove(id);
        return "DELETE FROM " + tableName + " WHERE id = " + id + ";";
    }

    private void assertDuplicateInsertFails(EngineApi.DatabaseEngine engine, Random random,
                                            TreeMap<Integer, ExpectedRow> expected, int step) {
        if (expected.isEmpty()) {
            applySingleMutation(engine, random, expected, step);
            return;
        }
        int id = pickExistingId(expected, random);
        ExpectedRow row = rowFor(id, step + 50_000);
        try (EngineApi.Session session = engine.openSession()) {
            assertThrows(Common.DatabaseException.class, () -> execute(session,
                    "INSERT INTO kv VALUES (" + id + ", " + sqlLiteral(row.payload()) + ", "
                            + sqlLiteralOrNull(row.note()) + ", " + row.score() + ");"));
        }
    }

    private void assertEngineMatchesExpected(EngineApi.DatabaseEngine engine, NavigableMap<Integer, ExpectedRow> expected) {
        assertEngineMatchesExpected(engine, "kv", expected);
    }

    private void assertEngineMatchesExpected(EngineApi.DatabaseEngine engine, String tableName,
                                             NavigableMap<Integer, ExpectedRow> expected) {
        try (EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult result = execute(session,
                    "SELECT id, payload, note, score FROM " + tableName + " ORDER BY id;").statements().getFirst();
            assertEquals(expected.size(), result.batch().rows().size());
            int index = 0;
            for (var entry : expected.entrySet()) {
                Common.ResultRow row = result.batch().rows().get(index++);
                assertEquals(entry.getKey().intValue(), row.get(0).asInt());
                assertEquals(entry.getValue().payload(), row.get(1).asText());
                assertEquals(entry.getValue().note(), row.get(2).asText());
                assertEquals(entry.getValue().score(), row.get(3).asInt());
            }
        }
    }

    private void assertCountMatches(EngineApi.DatabaseEngine engine, int expectedCount) {
        try (EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult result = execute(session, "SELECT COUNT(*) AS total FROM kv;").statements().getFirst();
            assertEquals((long) expectedCount, result.batch().rows().getFirst().get(0).asLong());
        }
    }

    private EngineApi.BatchResult execute(EngineApi.DatabaseEngine engine, String sql) {
        try (EngineApi.Session session = engine.openSession()) {
            return execute(session, sql);
        }
    }

    private EngineApi.BatchResult execute(EngineApi.Session session, String sql) {
        return session.execute(sql);
    }

    private ExpectedRow rowFor(int id, int step) {
        String payload = buildPayload(id, step, step % 19 == 0 ? 12_000 : 128 + (step % 64));
        String note = step % 3 == 0 ? null : "note_" + id + "_" + step;
        int score = Math.floorMod(step * 37 + id * 11, 10_000);
        return new ExpectedRow(payload, note, score);
    }

    private String buildPayload(int id, int step, int extraLength) {
        return "payload_" + id + "_" + step + "_" + "x".repeat(extraLength);
    }

    private int findMissingId(TreeMap<Integer, ExpectedRow> expected, Random random) {
        List<Integer> missing = new ArrayList<>();
        for (int id = 1; id <= MAX_ID; id++) {
            if (!expected.containsKey(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return -1;
        }
        return missing.get(random.nextInt(missing.size()));
    }

    private int pickExistingId(TreeMap<Integer, ExpectedRow> expected, Random random) {
        List<Integer> ids = new ArrayList<>(expected.keySet());
        return ids.get(random.nextInt(ids.size()));
    }

    private String sqlLiteral(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private String sqlLiteralOrNull(String text) {
        return text == null ? "NULL" : sqlLiteral(text);
    }

    private record ExpectedRow(String payload, String note, int score) {
    }
}
