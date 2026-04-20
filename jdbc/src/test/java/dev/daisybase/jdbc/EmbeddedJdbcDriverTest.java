package dev.daisybase.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.StringReader;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.CallableStatement;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Date;
import java.math.BigDecimal;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedJdbcDriverTest {
    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName(DaisyBaseDriver.class.getName());
    }

    @TempDir
    Path tempDir;

    @Test
    void embeddedDriverManagerFlowSupportsPreparedStatementsMetadataAndMultiResults() throws Exception {
        String url = "jdbc:daisybase:embedded:" + tempDir;
        try (Connection connection = DriverManager.getConnection(url)) {
            assertTrue(connection.getAutoCommit());
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, active BOOLEAN NOT NULL);");
                statement.execute("CREATE UNIQUE INDEX users_name_idx ON users (name);");
            }

            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO users VALUES (?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "Ada");
                insert.setBoolean(3, true);
                insert.addBatch();
                insert.setInt(1, 2);
                insert.setString(2, "Grace");
                insert.setBoolean(3, false);
                insert.addBatch();
                assertEquals(2, insert.executeBatch().length);
            }

            var savepoint = connection.setSavepoint("after_seed");
            try (PreparedStatement update = connection.prepareStatement("UPDATE users SET name = ? WHERE id = ?")) {
                update.setString(1, "Ada Lovelace");
                update.setInt(2, 1);
                assertEquals(1, update.executeUpdate());
            }
            connection.rollback(savepoint);
            connection.commit();

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id, name FROM users WHERE active = ? ORDER BY id")) {
                query.setBoolean(1, true);
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                    assertEquals("Ada", resultSet.getString(2));
                    assertFalse(resultSet.next());
                }
            }

            try (Statement statement = connection.createStatement()) {
                assertFalse(statement.execute("INSERT INTO users VALUES (3, 'Linus', TRUE); SELECT COUNT(*) AS total FROM users;"));
                assertEquals(1, statement.getUpdateCount());
                assertTrue(statement.getMoreResults());
                try (ResultSet resultSet = statement.getResultSet()) {
                    assertTrue(resultSet.next());
                    assertEquals(3L, resultSet.getLong(1));
                }
            }

            try (Statement statement = connection.createStatement();
                 ResultSet explain = statement.executeQuery("EXPLAIN SELECT id FROM users WHERE name = 'Ada';")) {
                assertTrue(explain.next());
                assertTrue(explain.getString(1).contains("IndexLookup"));
            }

            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, "public", "users", new String[]{"TABLE"})) {
                assertTrue(tables.next());
                assertEquals("users", tables.getString("TABLE_NAME"));
            }
            try (ResultSet typeInfo = metaData.getTypeInfo()) {
                boolean sawInteger = false;
                while (typeInfo.next()) {
                    if ("INTEGER".equals(typeInfo.getString("TYPE_NAME"))) {
                        sawInteger = true;
                    }
                }
                assertTrue(sawInteger);
            }
            try (ResultSet columns = metaData.getColumns(null, "public", "users", "%")) {
                int count = 0;
                while (columns.next()) {
                    count++;
                }
                assertEquals(3, count);
            }
            try (ResultSet primaryKeys = metaData.getPrimaryKeys(null, "public", "users")) {
                assertTrue(primaryKeys.next());
                assertEquals("id", primaryKeys.getString("COLUMN_NAME"));
            }
            try (ResultSet indexes = metaData.getIndexInfo(null, "public", "users", false, false)) {
                assertTrue(indexes.next());
                assertEquals("users_name_idx", indexes.getString("INDEX_NAME"));
            }
            assertTrue(metaData.supportsNamedParameters());
            assertTrue(metaData.supportsMultipleResultSets());
            assertTrue(metaData.supportsMultipleOpenResults());
            assertTrue(metaData.getNumericFunctions().contains("ABS"));
            assertTrue(metaData.getStringFunctions().contains("REPLACE"));
            assertEquals("", metaData.getTimeDateFunctions());

            assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> connection.createStatement().execute("INSERT INTO users VALUES (1, 'Dup', TRUE);"));
            assertThrows(SQLSyntaxErrorException.class,
                    () -> connection.createStatement().execute("SELECT 'broken"));
        }
    }

    @Test
    void dataSourceCreatesEmbeddedConnections() throws Exception {
        DaisyBaseDataSource dataSource = new DaisyBaseDataSource();
        dataSource.setUrl("jdbc:daisybase:embedded:" + tempDir);
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(1));
        }
    }

    @Test
    void supportsCommonJdbcSemanticsUsedByFrameworks() throws Exception {
        String url = "jdbc:daisybase:embedded:" + tempDir;
        Executor directExecutor = Runnable::run;
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setClientInfo("ApplicationName", "jdbc-test");
            assertEquals("jdbc-test", connection.getClientInfo("ApplicationName"));

            Properties clientInfo = new Properties();
            clientInfo.setProperty("traceId", "abc-123");
            connection.setClientInfo(clientInfo);
            assertNull(connection.getClientInfo("ApplicationName"));
            assertEquals("abc-123", connection.getClientInfo("traceId"));

                connection.setNetworkTimeout(directExecutor, 3210);
                assertEquals(3210, connection.getNetworkTimeout());

                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE events (" +
                            "id BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 10 INCREMENT BY 5), " +
                            "event_date DATE NOT NULL, event_time TIME NOT NULL, " +
                            "event_ts TIMESTAMP NOT NULL, amount DECIMAL(12, 2) NOT NULL, " +
                            "event_url TEXT NOT NULL, body TEXT NOT NULL);");
                }

                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO events (event_date, event_time, event_ts, amount, event_url, body) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setDate(1, Date.valueOf("2026-04-19"));
                insert.setTime(2, Time.valueOf("10:15:30"));
                insert.setTimestamp(3, Timestamp.valueOf("2026-04-19 10:15:30"));
                insert.setBigDecimal(4, new BigDecimal("42.75"));
                insert.setURL(5, URI.create("https://example.com/docs").toURL());
                insert.setCharacterStream(6, new StringReader("hello from jdbc"));
                assertEquals(1, insert.executeUpdate());
                try (ResultSet generatedKeys = insert.getGeneratedKeys()) {
                    assertTrue(generatedKeys.next());
                    assertEquals(10L, generatedKeys.getLong(1));
                    assertFalse(generatedKeys.next());
                }
            }

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT event_date, event_time, event_ts, amount, event_url, body FROM events WHERE id = ?")) {
                var metaData = query.getMetaData();
                var parameterMetaData = query.getParameterMetaData();
                assertEquals(6, metaData.getColumnCount());
                assertEquals("amount", metaData.getColumnLabel(4));
                assertEquals(12, metaData.getPrecision(4));
                assertEquals(2, metaData.getScale(4));
                assertEquals(1, parameterMetaData.getParameterCount());
                assertEquals(java.sql.Types.BIGINT, parameterMetaData.getParameterType(1));
                query.setLong(1, 10L);
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(Date.valueOf("2026-04-19"), resultSet.getDate(1));
                    assertEquals(Time.valueOf("10:15:30"), resultSet.getTime(2));
                    assertEquals(Timestamp.valueOf("2026-04-19 10:15:30"), resultSet.getTimestamp(3));
                    assertEquals(new BigDecimal("42.75"), resultSet.getBigDecimal(4));
                    assertEquals("https://example.com/docs", resultSet.getString(5));
                    assertEquals("hello from jdbc", resultSet.getString(6));
                }
            }

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "INSERT INTO events (event_date, event_time, event_ts, amount, event_url, body) VALUES " +
                                "(DATE '2026-04-20', TIME '11:00:00', TIMESTAMP '2026-04-20T11:00:00', 9.5, 'https://example.com/2', 'two')",
                        Statement.RETURN_GENERATED_KEYS));
                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    assertTrue(generatedKeys.next());
                    assertEquals(15L, generatedKeys.getLong(1));
                    assertFalse(generatedKeys.next());
                }
            }

            Statement statement = connection.createStatement();
            statement.closeOnCompletion();
            ResultSet resultSet = statement.executeQuery("SELECT id FROM events ORDER BY id");
            assertFalse(statement.isClosed());
            resultSet.close();
            assertTrue(statement.isClosed());

            try (Statement multi = connection.createStatement()) {
                assertTrue(multi.execute("SELECT id FROM events WHERE id = 10; SELECT id FROM events WHERE id = 15;"));
                ResultSet first = multi.getResultSet();
                assertTrue(first.next());
                assertEquals(10, first.getInt(1));
                assertTrue(multi.getMoreResults(Statement.KEEP_CURRENT_RESULT));
                ResultSet second = multi.getResultSet();
                assertTrue(second.next());
                assertEquals(15, second.getInt(1));
                assertFalse(first.isClosed());
                first.beforeFirst();
                assertTrue(first.next());
                assertEquals(10, first.getInt(1));
                first.close();
                second.close();
            }
        }
    }

    @Test
    void updatableResultSetsSupportSingleTablePrimaryKeyQueries() throws Exception {
        String url = "jdbc:daisybase:embedded:" + tempDir;
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE inventory (id INT PRIMARY KEY, name TEXT NOT NULL, qty INT NOT NULL);");
                statement.execute("INSERT INTO inventory VALUES (1, 'one', 10), (2, 'two', 20);");
            }

            assertTrue(connection.getMetaData().supportsResultSetConcurrency(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE));

            try (Statement statement = connection.createStatement(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
                 ResultSet resultSet = statement.executeQuery("SELECT id, name, qty FROM inventory ORDER BY id")) {
                assertEquals(ResultSet.CONCUR_UPDATABLE, resultSet.getConcurrency());
                assertTrue(resultSet.next());
                resultSet.updateString("name", "uno");
                resultSet.updateInt("qty", 11);
                resultSet.updateRow();

                assertTrue(resultSet.next());
                resultSet.deleteRow();

                resultSet.moveToInsertRow();
                resultSet.updateInt(1, 3);
                resultSet.updateString(2, "tres");
                resultSet.updateInt(3, 30);
                resultSet.insertRow();
                resultSet.moveToCurrentRow();
            }

            try (Statement mutate = connection.createStatement()) {
                mutate.execute("UPDATE inventory SET qty = 99 WHERE id = 1");
            }

            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT id, name, qty FROM inventory WHERE id = ?",
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
                var parameterMetaData = preparedStatement.getParameterMetaData();
                assertEquals(java.sql.Types.INTEGER, parameterMetaData.getParameterType(1));
                preparedStatement.setInt(1, 1);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    assertEquals(ResultSet.CONCUR_UPDATABLE, resultSet.getConcurrency());
                    assertTrue(resultSet.next());
                    resultSet.refreshRow();
                    assertEquals(99, resultSet.getInt("qty"));
                }
            }

            try (Statement readOnly = connection.createStatement(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
                 ResultSet resultSet = readOnly.executeQuery("SELECT id, qty + 1 AS next_qty FROM inventory ORDER BY id")) {
                assertEquals(ResultSet.CONCUR_READ_ONLY, resultSet.getConcurrency());
            }

            try (Statement verify = connection.createStatement();
                 ResultSet resultSet = verify.executeQuery("SELECT id, name, qty FROM inventory ORDER BY id")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
                assertEquals("uno", resultSet.getString(2));
                assertEquals(99, resultSet.getInt(3));
                assertTrue(resultSet.next());
                assertEquals(3, resultSet.getInt(1));
                assertEquals("tres", resultSet.getString(2));
                assertEquals(30, resultSet.getInt(3));
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void callableStatementsExecuteProceduresFunctionsAndExposeMetadata() throws Exception {
        String url = "jdbc:daisybase:embedded:" + tempDir;
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE jobs (
                            id INT PRIMARY KEY,
                            status TEXT NOT NULL
                        );
                        """);
                statement.execute("INSERT INTO jobs VALUES (1, 'pending');");
                statement.execute("""
                        CREATE FUNCTION public.add_fee(p_amount DECIMAL(12, 2)) RETURN DECIMAL(12, 2) IS
                        BEGIN
                          RETURN p_amount + 1.25;
                        END;
                        """);
                statement.execute("""
                        CREATE PROCEDURE public.finish_job(p_id INT, p_msg OUT TEXT, p_total INOUT DECIMAL(12, 2)) AS
                        BEGIN
                          UPDATE jobs SET status = 'done' WHERE id = p_id;
                          p_msg := 'done';
                          p_total := p_total + 1.25;
                        END;
                        """);
            }

            try (CallableStatement callable = connection.prepareCall("{call public.finish_job(?, ?, ?)}")) {
                ParameterMetaData parameterMetaData = callable.getParameterMetaData();
                assertEquals(3, parameterMetaData.getParameterCount());
                assertEquals(ParameterMetaData.parameterModeIn, parameterMetaData.getParameterMode(1));
                assertEquals(ParameterMetaData.parameterModeOut, parameterMetaData.getParameterMode(2));
                assertEquals(ParameterMetaData.parameterModeInOut, parameterMetaData.getParameterMode(3));
                assertEquals(java.sql.Types.DECIMAL, parameterMetaData.getParameterType(3));
                assertEquals("DECIMAL", parameterMetaData.getParameterTypeName(3));
                assertEquals(12, parameterMetaData.getPrecision(3));
                assertEquals(2, parameterMetaData.getScale(3));
                callable.setInt(1, 1);
                callable.registerOutParameter(2, java.sql.Types.VARCHAR);
                callable.setBigDecimal(3, new BigDecimal("4.75"));
                callable.registerOutParameter(3, java.sql.Types.DECIMAL);
                assertFalse(callable.execute());
                assertEquals("done", callable.getString(2));
                assertEquals(0, new BigDecimal("6.00").compareTo(callable.getBigDecimal(3)));
            }

            try (PreparedStatement query = connection.prepareStatement("SELECT status FROM jobs WHERE id = ?")) {
                query.setInt(1, 1);
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("done", resultSet.getString(1));
                }
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.add_fee(?)}")) {
                ParameterMetaData parameterMetaData = callable.getParameterMetaData();
                assertEquals(2, parameterMetaData.getParameterCount());
                assertEquals(ParameterMetaData.parameterModeOut, parameterMetaData.getParameterMode(1));
                assertEquals(java.sql.Types.DECIMAL, parameterMetaData.getParameterType(1));
                assertEquals(12, parameterMetaData.getPrecision(1));
                assertEquals(2, parameterMetaData.getScale(1));
                assertEquals(ParameterMetaData.parameterModeIn, parameterMetaData.getParameterMode(2));
                callable.registerOutParameter(1, java.sql.Types.DECIMAL);
                callable.setBigDecimal(2, new BigDecimal("2.50"));
                assertFalse(callable.execute());
                assertEquals(0, new BigDecimal("3.75").compareTo(callable.getBigDecimal(1)));
            }

            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet procedures = metaData.getProcedures(null, "public", "finish_job")) {
                assertTrue(procedures.next());
                assertEquals("finish_job", procedures.getString("PROCEDURE_NAME"));
            }
            try (ResultSet procedureColumns = metaData.getProcedureColumns(null, "public", "finish_job", "%")) {
                java.util.Set<String> columnNames = new java.util.LinkedHashSet<>();
                while (procedureColumns.next()) {
                    columnNames.add(procedureColumns.getString("COLUMN_NAME"));
                    if ("p_total".equals(procedureColumns.getString("COLUMN_NAME"))) {
                        assertEquals(12, procedureColumns.getInt("PRECISION"));
                        assertEquals(2, procedureColumns.getInt("SCALE"));
                    }
                }
                assertEquals(java.util.Set.of("p_id", "p_msg", "p_total"), columnNames);
            }
            try (ResultSet functions = metaData.getFunctions(null, "public", "add_fee")) {
                assertTrue(functions.next());
                assertEquals("add_fee", functions.getString("FUNCTION_NAME"));
            }
            try (ResultSet functionColumns = metaData.getFunctionColumns(null, "public", "add_fee", "%")) {
                java.util.Set<String> columnNames = new java.util.LinkedHashSet<>();
                while (functionColumns.next()) {
                    columnNames.add(functionColumns.getString("COLUMN_NAME"));
                    if ("RETURN_VALUE".equals(functionColumns.getString("COLUMN_NAME"))
                            || "p_amount".equals(functionColumns.getString("COLUMN_NAME"))) {
                        assertEquals(12, functionColumns.getInt("PRECISION"));
                        assertEquals(2, functionColumns.getInt("SCALE"));
                    }
                }
                assertEquals(java.util.Set.of("RETURN_VALUE", "p_amount"), columnNames);
            }
            assertTrue(metaData.allProceduresAreCallable());
        }
    }
}
