package dev.javadb.engine;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.sql.ReferencePlsqlParserBridge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void durableRoundTripPersistsRowsAcrossRestart() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, age INT CHECK (age >= 0));");
            session.execute("CREATE UNIQUE INDEX users_name_idx ON users (name);");
            session.execute("INSERT INTO users VALUES (1, 'Ada', 36), (2, 'Grace', 40);");
            session.execute("UPDATE users SET age = age + 1 WHERE id = 1;");
            session.execute("DELETE FROM users WHERE id = 2;");
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult result = session.execute("SELECT id, name, age FROM users ORDER BY id;").statements().getFirst();
            assertEquals(1, result.batch().rows().size());
            assertEquals(1, result.batch().rows().getFirst().get(0).asInt());
            assertEquals("Ada", result.batch().rows().getFirst().get(1).asText());
            assertEquals(37, result.batch().rows().getFirst().get(2).asInt());
        }
    }

    @Test
    void repeatableReadHoldsTransactionSnapshot() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session setup = engine.openSession();
             EngineApi.Session reader = engine.openSession();
             EngineApi.Session writer = engine.openSession()) {
            setup.execute("CREATE TABLE items (id INT PRIMARY KEY, name TEXT NOT NULL);");

            reader.execute("BEGIN ISOLATION LEVEL REPEATABLE READ;");
            assertEquals(0L, scalarLong(reader.execute("SELECT COUNT(*) AS total FROM items;")));

            writer.execute("INSERT INTO items VALUES (1, 'one');");

            assertEquals(0L, scalarLong(reader.execute("SELECT COUNT(*) AS total FROM items;")));
            reader.execute("COMMIT;");
            assertEquals(1L, scalarLong(setup.execute("SELECT COUNT(*) AS total FROM items;")));
        }
    }

    @Test
    void failedStatementInsideTransactionRollsBackToStatementBoundary() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT UNIQUE);");
            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1, 'Ada');");
            assertThrows(Common.DatabaseException.class, () -> session.execute("INSERT INTO users VALUES (1, 'Dup');"));
            session.execute("INSERT INTO users VALUES (2, 'Grace');");
            session.execute("COMMIT;");

            EngineApi.StatementResult result = session.execute("SELECT COUNT(*) AS total FROM users;").statements().getFirst();
            assertEquals(2L, result.batch().rows().getFirst().get(0).asLong());
        }
    }

    @Test
    void explainChoosesIndexLookupForEqualityPredicate() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL);");
            session.execute("CREATE INDEX users_name_idx ON users (name);");
            session.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Grace');");
            EngineApi.StatementResult explain = session.execute("EXPLAIN SELECT id, name FROM users WHERE name = 'Ada';").statements().getFirst();
            assertTrue(explain.explainPlan().contains("IndexLookup"));
        }
    }

    @Test
    void nativeOracleStyleParsingSupportsConstraintsAndFetch() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE TABLE users (
                        id NUMBER,
                        name VARCHAR2(30) NOT NULL,
                        age NUMBER,
                        CONSTRAINT users_pk PRIMARY KEY (id),
                        CONSTRAINT users_name_uq UNIQUE (name),
                        CONSTRAINT users_age_ck CHECK (age >= 0)
                    );
                    """);

            session.execute("INSERT INTO users VALUES (1, 'Ada', 36);");
            assertThrows(Common.DatabaseException.class, () -> session.execute("INSERT INTO users VALUES (1, 'Grace', 40);"));
            assertThrows(Common.DatabaseException.class, () -> session.execute("INSERT INTO users VALUES (2, 'Ada', 40);"));
            assertThrows(Common.DatabaseException.class, () -> session.execute("INSERT INTO users VALUES (3, 'Bad', -1);"));

            EngineApi.StatementResult firstRow = session.execute(
                    "SELECT id, name FROM users ORDER BY id FETCH FIRST 1 ROW ONLY;").statements().getFirst();
            assertEquals(1, firstRow.batch().rows().size());
            assertEquals(1, firstRow.batch().rows().getFirst().get(0).asInt());
            assertEquals("Ada", firstRow.batch().rows().getFirst().get(1).asText());
        }
    }

    @Test
    void groupedCountQueriesSupportGroupByHavingAndCountExpr() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE payroll (department TEXT NOT NULL, salary INT);");
            session.execute("""
                    INSERT INTO payroll VALUES
                    ('SALES', 10),
                    ('SALES', NULL),
                    ('ENG', 20),
                    ('ENG', 30),
                    ('HR', NULL);
                    """);

            EngineApi.StatementResult grouped = session.execute("""
                    SELECT department, COUNT(*) AS total, COUNT(salary) AS paid
                    FROM payroll
                    GROUP BY department
                    HAVING COUNT(*) >= 2
                    ORDER BY department;
                    """).statements().getFirst();

            assertEquals(2, grouped.batch().rows().size());
            assertEquals("ENG", grouped.batch().rows().get(0).get(0).asText());
            assertEquals(2L, grouped.batch().rows().get(0).get(1).asLong());
            assertEquals(2L, grouped.batch().rows().get(0).get(2).asLong());
            assertEquals("SALES", grouped.batch().rows().get(1).get(0).asText());
            assertEquals(2L, grouped.batch().rows().get(1).get(1).asLong());
            assertEquals(1L, grouped.batch().rows().get(1).get(2).asLong());

            EngineApi.StatementResult scalar = session.execute(
                    "SELECT COUNT(salary) AS paid FROM payroll HAVING COUNT(salary) >= 3;").statements().getFirst();
            assertEquals(1, scalar.batch().rows().size());
            assertEquals(3L, scalar.batch().rows().getFirst().get(0).asLong());
        }
    }

    @Test
    void groupedAggregatesSupportSumMinAndMax() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE payroll (department TEXT NOT NULL, salary INT, note TEXT);");
            session.execute("""
                    INSERT INTO payroll VALUES
                    ('SALES', 10, 'b'),
                    ('SALES', NULL, 'a'),
                    ('ENG', 20, 'z'),
                    ('ENG', 30, 'm'),
                    ('HR', NULL, 'q');
                    """);

            EngineApi.StatementResult grouped = session.execute("""
                    SELECT department, SUM(salary) AS total, MIN(note) AS first_note, MAX(salary) AS highest
                    FROM payroll
                    GROUP BY department
                    HAVING MAX(salary) >= 10
                    ORDER BY SUM(salary);
                    """).statements().getFirst();

            assertEquals(2, grouped.batch().rows().size());
            assertEquals("SALES", grouped.batch().rows().get(0).get(0).asText());
            assertEquals(10, grouped.batch().rows().get(0).get(1).asInt());
            assertEquals("a", grouped.batch().rows().get(0).get(2).asText());
            assertEquals(10, grouped.batch().rows().get(0).get(3).asInt());
            assertEquals("ENG", grouped.batch().rows().get(1).get(0).asText());
            assertEquals(50, grouped.batch().rows().get(1).get(1).asInt());
            assertEquals("m", grouped.batch().rows().get(1).get(2).asText());
            assertEquals(30, grouped.batch().rows().get(1).get(3).asInt());

            EngineApi.StatementResult scalar = session.execute(
                    "SELECT SUM(salary) AS total, MIN(note) AS first_note, MAX(salary) AS highest FROM payroll;").statements().getFirst();
            assertEquals(1, scalar.batch().rows().size());
            assertEquals(60, scalar.batch().rows().getFirst().get(0).asInt());
            assertEquals("a", scalar.batch().rows().getFirst().get(1).asText());
            assertEquals(30, scalar.batch().rows().getFirst().get(2).asInt());

            EngineApi.StatementResult explain = session.execute(
                    "EXPLAIN SELECT department, SUM(salary) FROM payroll GROUP BY department ORDER BY department;").statements().getFirst();
            assertTrue(explain.explainPlan().contains("aggregate=true"));
            assertTrue(explain.explainPlan().contains("groupBy=1"));

            assertThrows(Common.DatabaseException.class,
                    () -> session.execute("SELECT department, SUM(salary) FROM payroll;"));
            assertThrows(Common.DatabaseException.class,
                    () -> session.execute("SELECT SUM(note) FROM payroll;"));
        }
    }

    @Test
    void scalarFunctionsAndConstantFoldingWorkEndToEnd() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, nickname TEXT, delta INT NOT NULL);");
            session.execute("CREATE INDEX users_name_idx ON users (name);");
            session.execute("""
                    INSERT INTO users VALUES
                    (1, 'ADA', NULL, -7),
                    (2, 'GRACE', 'Amazing Grace', 4);
                    """);

            EngineApi.StatementResult result = session.execute("""
                    SELECT id,
                           LOWER(name) AS lower_name,
                           UPPER(COALESCE(nickname, name)) AS label,
                           LENGTH(name) AS name_len,
                           ABS(delta) AS abs_delta,
                           NVL(nickname, name) AS display_name,
                           TRIM(REPLACE(SUBSTR(COALESCE(nickname, name), 1, 7), ' ', '_')) AS short_label
                    FROM users
                    ORDER BY id;
                    """).statements().getFirst();

            assertEquals(2, result.batch().rows().size());
            assertEquals("ada", result.batch().rows().get(0).get(1).asText());
            assertEquals("ADA", result.batch().rows().get(0).get(2).asText());
            assertEquals(3L, result.batch().rows().get(0).get(3).asLong());
            assertEquals(7, result.batch().rows().get(0).get(4).asInt());
            assertEquals("ADA", result.batch().rows().get(0).get(5).asText());
            assertEquals("ADA", result.batch().rows().get(0).get(6).asText());
            assertEquals("grace", result.batch().rows().get(1).get(1).asText());
            assertEquals("AMAZING GRACE", result.batch().rows().get(1).get(2).asText());
            assertEquals(5L, result.batch().rows().get(1).get(3).asLong());
            assertEquals(4, result.batch().rows().get(1).get(4).asInt());
            assertEquals("Amazing Grace", result.batch().rows().get(1).get(5).asText());
            assertEquals("Amazing", result.batch().rows().get(1).get(6).asText());

            EngineApi.StatementResult explain = session.execute(
                    "EXPLAIN SELECT id FROM users WHERE name = UPPER('ada');").statements().getFirst();
            assertTrue(explain.explainPlan().contains("IndexLookup"));

            EngineApi.StatementResult simplifiedExplain = session.execute(
                    "EXPLAIN SELECT id FROM users WHERE TRUE AND name = 'ADA';").statements().getFirst();
            assertTrue(simplifiedExplain.explainPlan().contains("IndexLookup"));

            EngineApi.StatementResult grouped = session.execute("""
                    SELECT LOWER(name) AS canonical_name, COUNT(*) AS total
                    FROM users
                    GROUP BY LOWER(name)
                    ORDER BY LOWER(name);
                    """).statements().getFirst();
            assertEquals(2, grouped.batch().rows().size());
            assertEquals("ada", grouped.batch().rows().get(0).get(0).asText());
            assertEquals(1L, grouped.batch().rows().get(0).get(1).asLong());
            assertEquals("grace", grouped.batch().rows().get(1).get(0).asText());
            assertEquals(1L, grouped.batch().rows().get(1).get(1).asLong());

            EngineApi.StatementResult average = session.execute(
                    "SELECT AVG(delta) AS avg_delta FROM users;").statements().getFirst();
            assertEquals(1, average.batch().rows().size());
            assertEquals(-1L, average.batch().rows().getFirst().get(0).asLong());
        }
    }

    @Test
    void optimizerUsesEmptyResultFastPathWithoutBreakingAggregates() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL);");
            session.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Grace');");

            EngineApi.StatementResult explain = session.execute(
                    "EXPLAIN SELECT id FROM users WHERE 1 = 0;").statements().getFirst();
            assertTrue(explain.explainPlan().contains("EmptyResult"));

            EngineApi.StatementResult empty = session.execute(
                    "SELECT id FROM users WHERE 1 = 0;").statements().getFirst();
            assertEquals(0, empty.batch().rows().size());

            EngineApi.StatementResult limited = session.execute(
                    "SELECT id FROM users ORDER BY id FETCH FIRST 0 ROWS ONLY;").statements().getFirst();
            assertEquals(0, limited.batch().rows().size());

            EngineApi.StatementResult aggregate = session.execute(
                    "SELECT COUNT(*) AS total FROM users WHERE 1 = 0;").statements().getFirst();
            assertEquals(1, aggregate.batch().rows().size());
            assertEquals(0L, aggregate.batch().rows().getFirst().get(0).asLong());
        }
    }

    @Test
    void referenceBridgeExecutesAnonymousBlocksAndJoinQueries() {
        withReferenceParser(() -> {
            try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
                 EngineApi.Session session = engine.openSession()) {
                session.execute("CREATE TABLE departments (id INT PRIMARY KEY, name TEXT NOT NULL);");
                session.execute("CREATE TABLE users (id INT PRIMARY KEY, dept_id INT NOT NULL, name TEXT NOT NULL);");
                session.execute("""
                        INSERT INTO departments VALUES (1, 'ENG'), (2, 'OPS');
                        INSERT INTO users VALUES (1, 1, 'Ada'), (2, 1, 'Grace'), (3, 2, 'Linus');
                        """);

                session.execute("""
                        BEGIN
                          INSERT INTO users VALUES (4, 2, 'Barbara');
                          UPDATE users SET name = 'Grace Hopper' WHERE id = 2;
                        END;
                        """);

                EngineApi.StatementResult join = session.execute("""
                        SELECT u.id AS user_id, d.name AS dept_name
                        FROM users u JOIN departments d ON u.dept_id = d.id
                        WHERE d.name = 'ENG'
                        ORDER BY u.id;
                        """).statements().getFirst();

                assertEquals(2, join.batch().rows().size());
                assertEquals(1, join.batch().rows().get(0).get(0).asInt());
                assertEquals("ENG", join.batch().rows().get(0).get(1).asText());
                assertEquals(2, join.batch().rows().get(1).get(0).asInt());
                assertEquals("ENG", join.batch().rows().get(1).get(1).asText());

                EngineApi.StatementResult exists = session.execute("""
                        SELECT id
                        FROM users
                        WHERE EXISTS (
                            SELECT 1
                            FROM departments d
                            WHERE d.id = users.dept_id AND d.name = 'OPS'
                        )
                        ORDER BY id;
                        """).statements().getFirst();

                assertEquals(2, exists.batch().rows().size());
                assertEquals(3, exists.batch().rows().get(0).get(0).asInt());
                assertEquals(4, exists.batch().rows().get(1).get(0).asInt());
            }
        });
    }

    @Test
    void referenceBridgeExecutesDerivedTablesAndGroupedJoinAggregates() {
        withReferenceParser(() -> {
            try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
                 EngineApi.Session session = engine.openSession()) {
                session.execute("CREATE TABLE departments (id INT PRIMARY KEY, name TEXT NOT NULL);");
                session.execute("CREATE TABLE users (id INT PRIMARY KEY, dept_id INT NOT NULL, name TEXT NOT NULL);");
                session.execute("""
                        INSERT INTO departments VALUES (1, 'ENG'), (2, 'OPS');
                        INSERT INTO users VALUES (1, 1, 'Ada'), (2, 1, 'Grace'), (3, 2, 'Linus'), (4, 2, 'Barbara');
                        """);

                EngineApi.StatementResult derived = session.execute("""
                        SELECT t.id
                        FROM (SELECT id FROM users WHERE id > 2) t
                        ORDER BY t.id;
                        """).statements().getFirst();

                assertEquals(2, derived.batch().rows().size());
                assertEquals(3, derived.batch().rows().get(0).get(0).asInt());
                assertEquals(4, derived.batch().rows().get(1).get(0).asInt());

                EngineApi.StatementResult grouped = session.execute("""
                        SELECT d.name AS dept_name, COUNT(*) AS total
                        FROM users u JOIN departments d ON u.dept_id = d.id
                        GROUP BY d.name
                        HAVING COUNT(*) >= 2
                        ORDER BY d.name;
                        """).statements().getFirst();

                assertEquals(2, grouped.batch().rows().size());
                assertEquals("ENG", grouped.batch().rows().get(0).get(0).asText());
                assertEquals(2L, grouped.batch().rows().get(0).get(1).asLong());
                assertEquals("OPS", grouped.batch().rows().get(1).get(0).asText());
                assertEquals(2L, grouped.batch().rows().get(1).get(1).asLong());
            }
        });
    }

    @Test
    void referenceQueryExplainShowsHashJoinAndSpillSort() {
        withReferenceParser(() -> {
            String previousHash = System.getProperty("javadb.execution.hashJoinThresholdRows");
            String previousSpill = System.getProperty("javadb.execution.sortSpillThresholdRows");
            try {
                System.setProperty("javadb.execution.hashJoinThresholdRows", "1");
                System.setProperty("javadb.execution.sortSpillThresholdRows", "2");
                try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
                     EngineApi.Session session = engine.openSession()) {
                    session.execute("CREATE TABLE departments (id INT PRIMARY KEY, name TEXT NOT NULL);");
                    session.execute("CREATE TABLE users (id INT PRIMARY KEY, dept_id INT NOT NULL, name TEXT NOT NULL);");
                    session.execute("""
                            INSERT INTO departments VALUES (1, 'ENG'), (2, 'OPS'), (3, 'SALES');
                            INSERT INTO users VALUES
                            (1, 1, 'Ada'),
                            (2, 1, 'Grace'),
                            (3, 2, 'Linus'),
                            (4, 2, 'Barbara'),
                            (5, 3, 'Margaret');
                            """);

                    EngineApi.StatementResult explain = session.execute("""
                            EXPLAIN
                            SELECT u.id, d.name
                            FROM users u JOIN departments d ON u.dept_id = d.id
                            ORDER BY d.name, u.id;
                            """).statements().getFirst();

                    assertTrue(explain.explainPlan().contains("ReferenceQuery"));
                    assertTrue(explain.explainPlan().contains("join=HASH"));
                    assertTrue(explain.explainPlan().contains("spillSort=true"));

                    EngineApi.StatementResult sorted = session.execute("""
                            SELECT u.id, d.name
                            FROM users u JOIN departments d ON u.dept_id = d.id
                            ORDER BY d.name, u.id;
                            """).statements().getFirst();
                    assertEquals(5, sorted.batch().rows().size());
                    assertEquals("ENG", sorted.batch().rows().get(0).get(1).asText());
                    assertEquals(1, sorted.batch().rows().get(0).get(0).asInt());
                    assertEquals("ENG", sorted.batch().rows().get(1).get(1).asText());
                    assertEquals(2, sorted.batch().rows().get(1).get(0).asInt());
                    assertEquals("OPS", sorted.batch().rows().get(2).get(1).asText());
                }
            } finally {
                restoreProperty("javadb.execution.hashJoinThresholdRows", previousHash);
                restoreProperty("javadb.execution.sortSpillThresholdRows", previousSpill);
            }
        });
    }

    @Test
    void storageManagerPersistsLargeOverflowValuesAcrossRestart() {
        String largeText = "doc-" + "R".repeat(40_000);
        String updatedText = "updated-" + "S".repeat(32_000);
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE docs (id INT PRIMARY KEY, body TEXT NOT NULL);");
            session.execute("INSERT INTO docs VALUES (1, '" + largeText + "'), (2, 'small');");
            session.execute("UPDATE docs SET body = '" + updatedText + "' WHERE id = 1;");
            session.execute("DELETE FROM docs WHERE id = 2;");
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult result = session.execute(
                    "SELECT id, body, LENGTH(body) AS body_len FROM docs ORDER BY id;").statements().getFirst();
            assertEquals(1, result.batch().rows().size());
            assertEquals(1, result.batch().rows().getFirst().get(0).asInt());
            assertEquals(updatedText, result.batch().rows().getFirst().get(1).asText());
            assertEquals((long) updatedText.length(), result.batch().rows().getFirst().get(2).asLong());
        }
    }

    @Test
    void catalogPersistsSequencesRoutinesAndIdentityDefinitionsAcrossRestart() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE SEQUENCE public.order_seq START WITH 10 INCREMENT BY 2 CACHE 8;");
            session.execute("""
                    CREATE TABLE orders (
                        id BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 100 INCREMENT BY 5),
                        customer_id BIGINT NOT NULL
                    );
                    """);
            session.execute("""
                    CREATE FUNCTION public.add_one(p_value BIGINT) RETURN BIGINT IS
                    BEGIN
                      RETURN p_value + 1;
                    END;
                    """);
            session.execute("""
                    CREATE PROCEDURE public.mark_ready(p_id BIGINT, p_msg OUT TEXT) AS
                    BEGIN
                      NULL;
                    END;
                    """);

            Catalog.CatalogSnapshot snapshot = ((EmbeddedDatabaseEngine) engine).catalogSnapshotForIntrospection();
            assertTrue(snapshot.sequence(new Catalog.QualifiedName("public", "order_seq")).isPresent());
            assertTrue(snapshot.routine(new Catalog.QualifiedName("public", "add_one")).isPresent());
            assertTrue(snapshot.routine(new Catalog.QualifiedName("public", "mark_ready")).isPresent());
            Catalog.TableDefinition orders = snapshot.requireTable(new Catalog.QualifiedName("public", "orders"));
            assertEquals(Catalog.IdentityGeneration.ALWAYS, orders.columns().getFirst().identityDefinition().generation());
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir)) {
            Catalog.CatalogSnapshot snapshot = ((EmbeddedDatabaseEngine) engine).catalogSnapshotForIntrospection();
            Catalog.SequenceDefinition sequence = snapshot.sequence(new Catalog.QualifiedName("public", "order_seq")).orElseThrow();
            assertEquals(10L, sequence.options().startWith());
            assertEquals(2L, sequence.options().incrementBy());
            assertEquals(8, sequence.options().cacheSize());
            assertTrue(snapshot.routine(new Catalog.QualifiedName("public", "add_one")).isPresent());
            assertTrue(snapshot.routine(new Catalog.QualifiedName("public", "mark_ready")).isPresent());
            Catalog.TableDefinition orders = snapshot.requireTable(new Catalog.QualifiedName("public", "orders"));
            assertEquals(100L, orders.columns().getFirst().identityDefinition().options().startWith());
        }
    }

    @Test
    void sequencesAndIdentityColumnsAllocateDurablyAndReturnGeneratedKeys() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE SEQUENCE public.order_seq START WITH 10 INCREMENT BY 2;");
            session.execute("CREATE TABLE sequence_probe (id INT PRIMARY KEY);");
            session.execute("INSERT INTO sequence_probe VALUES (1);");

            EngineApi.StatementResult nextValues = session.execute("""
                    SELECT NEXT VALUE FOR public.order_seq AS first_value,
                           NEXT VALUE FOR public.order_seq AS second_value
                    FROM sequence_probe;
                    """).statements().getFirst();
            assertEquals(10L, nextValues.batch().rows().getFirst().get(0).asLong());
            assertEquals(12L, nextValues.batch().rows().getFirst().get(1).asLong());

            session.execute("""
                    CREATE TABLE orders (
                        id BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 100 INCREMENT BY 5),
                        customer_id BIGINT NOT NULL
                    );
                    """);

            EngineApi.StatementResult inserted = session.execute(
                    "INSERT INTO orders (customer_id) VALUES (1), (2);").statements().getFirst();
            assertEquals(2L, inserted.updateCount());
            assertEquals(1, inserted.generatedKeys().columns().size());
            assertEquals(2, inserted.generatedKeys().rows().size());
            assertEquals(100L, inserted.generatedKeys().rows().get(0).get(0).asLong());
            assertEquals(105L, inserted.generatedKeys().rows().get(1).get(0).asLong());

            assertThrows(Common.DatabaseException.class,
                    () -> session.execute("INSERT INTO orders (id, customer_id) VALUES (999, 3);"));

            session.execute("""
                    CREATE TABLE tickets (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1 INCREMENT BY 1),
                        note TEXT NOT NULL
                    );
                    """);
            session.execute("INSERT INTO tickets (id, note) VALUES (50, 'manual');");
            EngineApi.StatementResult ticketInsert = session.execute(
                    "INSERT INTO tickets (note) VALUES ('generated');").statements().getFirst();
            assertEquals(1, ticketInsert.generatedKeys().rows().size());
            assertEquals(51L, ticketInsert.generatedKeys().rows().getFirst().get(0).asLong());
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult resumed = session.execute(
                    "SELECT NEXT VALUE FOR public.order_seq AS next_value FROM sequence_probe;").statements().getFirst();
            assertEquals(14L, resumed.batch().rows().getFirst().get(0).asLong());

            EngineApi.StatementResult orders = session.execute(
                    "SELECT id, customer_id FROM orders ORDER BY id;").statements().getFirst();
            assertEquals(2, orders.batch().rows().size());
            assertEquals(100L, orders.batch().rows().get(0).get(0).asLong());
            assertEquals(105L, orders.batch().rows().get(1).get(0).asLong());

            EngineApi.StatementResult tickets = session.execute(
                    "SELECT id, note FROM tickets ORDER BY id;").statements().getFirst();
            assertEquals(2, tickets.batch().rows().size());
            assertEquals(50L, tickets.batch().rows().get(0).get(0).asLong());
            assertEquals(51L, tickets.batch().rows().get(1).get(0).asLong());
        }
    }

    @Test
    void callExecutesProceduresFunctionsAndNestedRoutineExpressions() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE TABLE work_items (
                        id INT PRIMARY KEY,
                        status TEXT NOT NULL,
                        total DECIMAL NOT NULL
                    );
                    """);
            session.execute("INSERT INTO work_items VALUES (1, 'pending', 2.25);");
            session.execute("""
                    CREATE FUNCTION public.bump_total(p_amount DECIMAL) RETURN DECIMAL IS
                    BEGIN
                      RETURN p_amount + 0.75;
                    END;
                    """);
            session.execute("""
                    CREATE PROCEDURE public.mark_ready(p_id INT, p_msg OUT TEXT, p_total OUT DECIMAL) AS
                    BEGIN
                      UPDATE work_items SET status = 'ready', total = total + 0.75 WHERE id = p_id;
                      p_msg := UPPER('ready');
                      p_total := public.bump_total(5.25);
                    END;
                    """);

            EngineApi.StatementResult procedureCall = session.execute(
                    "CALL public.mark_ready(1, NULL, NULL);").statements().getFirst();
            assertEquals("CALL", procedureCall.commandTag());
            assertEquals(2, procedureCall.batch().columns().size());
            assertEquals("READY", procedureCall.batch().rows().getFirst().get(0).asText());
            assertDecimalEquals("6.00", procedureCall.batch().rows().getFirst().get(1).asDecimal());

            EngineApi.StatementResult functionCall = session.execute(
                    "CALL public.bump_total(2.25);").statements().getFirst();
            assertEquals("RETURN_VALUE", functionCall.batch().columns().getFirst().name());
            assertDecimalEquals("3.00", functionCall.batch().rows().getFirst().get(0).asDecimal());

            EngineApi.StatementResult stored = session.execute(
                    "SELECT status, total FROM work_items WHERE id = 1;").statements().getFirst();
            assertEquals("ready", stored.batch().rows().getFirst().get(0).asText());
            assertDecimalEquals("3.00", stored.batch().rows().getFirst().get(1).asDecimal());
        }
    }

    @Test
    void failedRoutineRollsBackToStatementBoundaryInsideExplicitTransaction() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE work_log (id INT PRIMARY KEY, message TEXT NOT NULL);");
            session.execute("""
                    CREATE PROCEDURE public.insert_twice(p_id INT) AS
                    BEGIN
                      INSERT INTO work_log VALUES (p_id, 'first');
                      INSERT INTO work_log VALUES (p_id, 'second');
                    END;
                    """);

            session.execute("BEGIN;");
            assertThrows(Common.DatabaseException.class, () -> session.execute("CALL public.insert_twice(1);"));
            session.execute("INSERT INTO work_log VALUES (2, 'after');");
            session.execute("COMMIT;");

            EngineApi.StatementResult rows = session.execute(
                    "SELECT id, message FROM work_log ORDER BY id;").statements().getFirst();
            assertEquals(1, rows.batch().rows().size());
            assertEquals(2, rows.batch().rows().getFirst().get(0).asInt());
            assertEquals("after", rows.batch().rows().getFirst().get(1).asText());
        }
    }

    @Test
    void decimalAndTemporalValuesRoundTripAggregateAndPersistAcrossRestart() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE TABLE ledger (
                        id INT PRIMARY KEY,
                        amount DECIMAL NOT NULL,
                        booked_on DATE NOT NULL,
                        booked_at TIMESTAMP NOT NULL,
                        booked_time TIME NOT NULL
                    );
                    """);
            session.execute("""
                    INSERT INTO ledger VALUES
                    (1, 12.50, DATE '2026-04-18', TIMESTAMP '2026-04-18T09:15:00', TIME '09:15:00'),
                    (2, 7.25, DATE '2026-04-19', TIMESTAMP '2026-04-19T10:30:00', TIME '10:30:00'),
                    (3, 4.75, DATE '2026-04-20', TIMESTAMP '2026-04-20T11:45:00', TIME '11:45:00');
                    """);

            EngineApi.StatementResult filtered = session.execute("""
                    SELECT id, amount, booked_on, booked_at, booked_time, amount + 1.25 AS adjusted
                    FROM ledger
                    WHERE booked_on >= DATE '2026-04-19'
                    ORDER BY amount DESC;
                    """).statements().getFirst();
            assertEquals(2, filtered.batch().rows().size());
            assertEquals(2, filtered.batch().rows().get(0).get(0).asInt());
            assertDecimalEquals("7.25", filtered.batch().rows().get(0).get(1).asDecimal());
            assertEquals(LocalDate.parse("2026-04-19"), filtered.batch().rows().get(0).get(2).asDate());
            assertEquals(LocalDateTime.parse("2026-04-19T10:30:00"), filtered.batch().rows().get(0).get(3).asTimestamp());
            assertEquals(LocalTime.parse("10:30:00"), filtered.batch().rows().get(0).get(4).asTime());
            assertDecimalEquals("8.50", filtered.batch().rows().get(0).get(5).asDecimal());

            EngineApi.StatementResult aggregates = session.execute("""
                    SELECT
                        SUM(amount) AS total_amount,
                        AVG(amount) AS average_amount,
                        MIN(booked_on) AS first_day,
                        MAX(booked_at) AS last_stamp
                    FROM ledger;
                    """).statements().getFirst();
            assertDecimalEquals("24.50", aggregates.batch().rows().getFirst().get(0).asDecimal());
            assertDecimalEquals("8.166666666666666666666666666666667",
                    aggregates.batch().rows().getFirst().get(1).asDecimal());
            assertEquals(LocalDate.parse("2026-04-18"), aggregates.batch().rows().getFirst().get(2).asDate());
            assertEquals(LocalDateTime.parse("2026-04-20T11:45:00"), aggregates.batch().rows().getFirst().get(3).asTimestamp());
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            EngineApi.StatementResult persisted = session.execute("""
                    SELECT amount, booked_on, booked_at, booked_time
                    FROM ledger
                    WHERE id = 1;
                    """).statements().getFirst();
            assertDecimalEquals("12.50", persisted.batch().rows().getFirst().get(0).asDecimal());
            assertEquals(LocalDate.parse("2026-04-18"), persisted.batch().rows().getFirst().get(1).asDate());
            assertEquals(LocalDateTime.parse("2026-04-18T09:15:00"), persisted.batch().rows().getFirst().get(2).asTimestamp());
            assertEquals(LocalTime.parse("09:15:00"), persisted.batch().rows().getFirst().get(3).asTime());
        }
    }

    @Test
    void executionControlTimesOutLongRunningQueries() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE slow_rows (id INT PRIMARY KEY, note TEXT NOT NULL);");
            seedRows(session, "slow_rows", 10_000);

            Common.DatabaseException timeout = assertThrows(Common.DatabaseException.class,
                    () -> session.execute("SELECT id FROM slow_rows ORDER BY id DESC;",
                            Common.ExecutionControl.timeoutMillis(1)));
            assertEquals(Common.ErrorCode.QUERY_TIMEOUT, timeout.code());

            EngineApi.StatementResult count = session.execute(
                    "SELECT COUNT(*) AS total FROM slow_rows;").statements().getFirst();
            assertEquals(10_000L, count.batch().rows().getFirst().get(0).asLong());
        }
    }

    @Test
    void preparedStatementsDescribeExecuteAndCloseWithTypedMetadata() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE TABLE ledger (
                        id INT PRIMARY KEY,
                        amount DECIMAL(12, 2) NOT NULL,
                        status TEXT NOT NULL
                    );
                    """);
            session.execute("INSERT INTO ledger VALUES (1, 12.50, 'pending');");
            session.execute("""
                    CREATE FUNCTION public.add_fee(p_amount DECIMAL(12, 2)) RETURN DECIMAL(12, 2) IS
                    BEGIN
                      RETURN p_amount + 1.25;
                    END;
                    """);
            session.execute("""
                    CREATE PROCEDURE public.finish_job(p_id INT, p_msg OUT TEXT, p_total INOUT DECIMAL(12, 2)) AS
                    BEGIN
                      UPDATE ledger SET status = 'done', amount = amount + 1.25 WHERE id = p_id;
                      p_msg := 'done';
                      p_total := p_total + 1.25;
                    END;
                    """);

            EngineApi.PreparedStatementDescription select = session.prepare(
                    "SELECT amount, status FROM ledger WHERE id = ?");
            assertEquals(1, select.parameterCount());
            assertEquals(Common.DataType.INTEGER, select.parameterDescriptions().getFirst().type());
            assertTrue(select.producesResultSet());
            assertEquals("amount", select.resultColumns().getFirst().name());
            assertEquals(12, select.resultColumns().getFirst().precision());
            assertEquals(2, select.resultColumns().getFirst().scale());

            EngineApi.StatementResult preparedSelect = session.executePrepared(
                    select.statementId(), java.util.List.of("1"), Common.ExecutionControl.none()).statements().getFirst();
            assertDecimalEquals("12.50", preparedSelect.batch().rows().getFirst().get(0).asDecimal());
            assertEquals("pending", preparedSelect.batch().rows().getFirst().get(1).asText());

            EngineApi.PreparedStatementDescription explain = session.prepare(
                    "EXPLAIN SELECT amount FROM ledger WHERE id = ?");
            assertEquals(Common.DataType.INTEGER, explain.parameterDescriptions().getFirst().type());
            assertEquals(1, explain.resultColumns().size());
            assertEquals("PLAN", explain.resultColumns().getFirst().name());
            EngineApi.StatementResult explained = session.executePrepared(
                    explain.statementId(), java.util.List.of("1"), Common.ExecutionControl.none()).statements().getFirst();
            assertFalse(explained.explainPlan().isBlank());

            EngineApi.PreparedStatementDescription procedure = session.prepare(
                    "CALL public.finish_job(?, ?, ?)");
            assertEquals(3, procedure.parameterCount());
            assertEquals(Common.DataType.INTEGER, procedure.parameterDescriptions().get(0).type());
            assertEquals(Common.DataType.TEXT, procedure.parameterDescriptions().get(1).type());
            assertEquals(Common.DataType.DECIMAL, procedure.parameterDescriptions().get(2).type());
            assertEquals(2, procedure.resultColumns().size());
            assertEquals("p_msg", procedure.resultColumns().get(0).name());
            assertEquals("p_total", procedure.resultColumns().get(1).name());
            assertEquals(12, procedure.resultColumns().get(1).precision());
            assertEquals(2, procedure.resultColumns().get(1).scale());

            EngineApi.StatementResult procedureCall = session.executePrepared(
                    procedure.statementId(),
                    java.util.List.of("1", "NULL", "4.75"),
                    Common.ExecutionControl.none()).statements().getFirst();
            assertEquals("done", procedureCall.batch().rows().getFirst().get(0).asText());
            assertDecimalEquals("6.00", procedureCall.batch().rows().getFirst().get(1).asDecimal());

            EngineApi.PreparedStatementDescription function = session.prepare("CALL public.add_fee(?)");
            assertEquals(Common.DataType.DECIMAL, function.parameterDescriptions().getFirst().type());
            assertEquals("RETURN_VALUE", function.resultColumns().getFirst().name());
            assertEquals(12, function.resultColumns().getFirst().precision());
            assertEquals(2, function.resultColumns().getFirst().scale());
            EngineApi.StatementResult functionCall = session.executePrepared(
                    function.statementId(), java.util.List.of("2.50"), Common.ExecutionControl.none()).statements().getFirst();
            assertDecimalEquals("3.75", functionCall.batch().rows().getFirst().get(0).asDecimal());

            session.closePrepared(select.statementId());
            Common.DatabaseException unknownPrepared = assertThrows(Common.DatabaseException.class,
                    () -> session.executePrepared(select.statementId(), java.util.List.of("1"), Common.ExecutionControl.none()));
            assertEquals(Common.ErrorCode.SEMANTIC_ERROR, unknownPrepared.code());

            Common.DatabaseException wrongArity = assertThrows(Common.DatabaseException.class,
                    () -> session.executePrepared(procedure.statementId(), java.util.List.of("1"), Common.ExecutionControl.none()));
            assertEquals(Common.ErrorCode.SEMANTIC_ERROR, wrongArity.code());

            Common.DatabaseException badTemplate = assertThrows(Common.DatabaseException.class,
                    () -> session.prepare("SELECT ?; SELECT 2"));
            assertEquals(Common.ErrorCode.PARSE_ERROR, badTemplate.code());
        }
    }

    private long scalarLong(EngineApi.BatchResult result) {
        return result.statements().getFirst().batch().rows().getFirst().get(0).asLong();
    }

    private void seedRows(EngineApi.Session session, String tableName, int rowCount) {
        int batchSize = 250;
        for (int start = 1; start <= rowCount; start += batchSize) {
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" VALUES ");
            int end = Math.min(rowCount, start + batchSize - 1);
            for (int id = start; id <= end; id++) {
                if (id > start) {
                    sql.append(", ");
                }
                sql.append("(").append(id).append(", 'row-").append(id).append("')");
            }
            session.execute(sql.toString());
        }
    }

    private void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private void withReferenceParser(Runnable runnable) {
        Assumptions.assumeTrue(ReferencePlsqlParserBridge.isUsableForTests());
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "auto");
            runnable.run();
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
