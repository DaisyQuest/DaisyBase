package dev.daisybase.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineIntrospectionTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesSchemasTablesColumnsPrimaryKeysAndIndexes() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, active BOOLEAN NOT NULL);");
            session.execute("CREATE UNIQUE INDEX users_name_idx ON users (name);");

            var schemas = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.SCHEMAS, java.util.List.of("%"));
            var tables = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.TABLES, java.util.List.of("public", "users", "TABLE"));
            var columns = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.COLUMNS, java.util.List.of("public", "users", "%"));
            var primaryKeys = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.PRIMARY_KEYS, java.util.List.of("public", "users"));
            var indexes = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.INDEX_INFO, java.util.List.of("public", "users", "false"));

            assertEquals("public", schemas.rows().getFirst().get(0).asText());
            assertEquals("users", tables.rows().getFirst().get(2).asText());
            assertEquals(3, columns.rows().size());
            assertEquals("id", primaryKeys.rows().getFirst().get(3).asText());
            assertTrue(indexes.rows().stream().anyMatch(row -> "users_name_idx".equals(row.get(5).asText())));
        }
    }

    @Test
    void appliesPatternsUniqueOnlyAndRejectsUnsupportedEngineTypes() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, active BOOLEAN NOT NULL);");
            session.execute("CREATE UNIQUE INDEX users_name_idx ON users (name);");

            var filteredColumns = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.COLUMNS,
                    java.util.List.of("pub_ic", "us_rs", "na_e"));
            var uniqueIndexes = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.INDEX_INFO,
                    java.util.List.of("public", "users", "true"));
            var wrongType = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.TABLES,
                    java.util.List.of("public", "users", "VIEW"));

            assertEquals(1, filteredColumns.rows().size());
            assertEquals("name", filteredColumns.rows().getFirst().get(3).asText());
            assertEquals(1, uniqueIndexes.rows().size());
            assertEquals("users_name_idx", uniqueIndexes.rows().getFirst().get(5).asText());
            assertTrue(wrongType.rows().isEmpty());
        }

        EngineApi.DatabaseEngine unsupported = new EngineApi.DatabaseEngine() {
            @Override
            public EngineApi.Session openSession() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void close() {
            }
        };
        assertThrows(RuntimeException.class,
                () -> EngineIntrospection.query(unsupported, EngineIntrospection.MetadataQuery.SCHEMAS, java.util.List.of("%")));
    }

    @Test
    void marksIdentityColumnsAsAutoIncrement() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE TABLE orders (
                        id BIGINT GENERATED ALWAYS AS IDENTITY,
                        customer_id BIGINT NOT NULL
                    );
                    """);

            var columns = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.COLUMNS,
                    java.util.List.of("public", "orders", "%"));

            assertEquals(2, columns.rows().size());
            assertEquals("YES", columns.rows().getFirst().get(18).asText());
            assertEquals("YES", columns.rows().getFirst().get(19).asText());
            assertEquals("NO", columns.rows().get(1).get(18).asText());
        }
    }

    @Test
    void exposesTypedDecimalAndTemporalMetadata() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE TABLE metrics (
                        id INT PRIMARY KEY,
                        amount DECIMAL(12, 4) NOT NULL,
                        booked_on DATE NOT NULL,
                        booked_at TIMESTAMP NOT NULL,
                        booked_time TIME NOT NULL
                    );
                    """);

            var columns = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.COLUMNS,
                    java.util.List.of("public", "metrics", "%"));

            assertEquals(5, columns.rows().size());
            assertEquals("DECIMAL", columns.rows().get(1).get(5).asText());
            assertEquals(12, columns.rows().get(1).get(6).asInt());
            assertEquals(4, columns.rows().get(1).get(8).asInt());
            assertEquals("DATE", columns.rows().get(2).get(5).asText());
            assertEquals("TIMESTAMP", columns.rows().get(3).get(5).asText());
            assertEquals("TIME", columns.rows().get(4).get(5).asText());
        }
    }

    @Test
    void exposesProcedureAndFunctionMetadata() {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("""
                    CREATE FUNCTION public.add_fee(p_amount DECIMAL(12, 2)) RETURN DECIMAL(12, 2) IS
                    BEGIN
                      RETURN p_amount + 1.25;
                    END;
                    """);
            session.execute("""
                    CREATE PROCEDURE public.finish_job(p_id INT, p_msg OUT TEXT, p_total INOUT DECIMAL(12, 2)) AS
                    BEGIN
                      p_msg := 'done';
                      p_total := p_total + 1.25;
                    END;
                    """);

            var procedures = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.PROCEDURES,
                    java.util.List.of("public", "finish_job"));
            var procedureColumns = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.PROCEDURE_COLUMNS,
                    java.util.List.of("public", "finish_job", "%"));
            var functions = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.FUNCTIONS,
                    java.util.List.of("public", "add_fee"));
            var functionColumns = EngineIntrospection.query(engine, EngineIntrospection.MetadataQuery.FUNCTION_COLUMNS,
                    java.util.List.of("public", "add_fee", "%"));

            assertEquals("finish_job", procedures.rows().getFirst().get(2).asText());
            assertEquals(3, procedureColumns.rows().size());
            assertEquals("p_msg", procedureColumns.rows().get(1).get(3).asText());
            assertEquals(12, procedureColumns.rows().get(2).get(7).asInt());
            assertEquals(2, procedureColumns.rows().get(2).get(9).asInt());
            assertEquals("add_fee", functions.rows().getFirst().get(2).asText());
            assertEquals("RETURN_VALUE", functionColumns.rows().getFirst().get(3).asText());
            assertEquals("DECIMAL", functionColumns.rows().getFirst().get(6).asText());
            assertEquals(12, functionColumns.rows().getFirst().get(7).asInt());
            assertEquals(2, functionColumns.rows().getFirst().get(9).asInt());
        }
    }
}
