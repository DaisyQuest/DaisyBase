package dev.javadb.jdbc;

import dev.javadb.engine.EmbeddedDatabaseEngine;
import dev.javadb.engine.EngineApi;
import dev.javadb.server.JavaDbServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteJdbcDriverTest {
    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName(JavaDbDriver.class.getName());
    }

    @TempDir
    Path tempDir;

    @Test
    void remoteDriverSupportsQueriesTransactionsSavepointsAndMetadata() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0);
             Connection connection = DriverManager.getConnection(
                     "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port())) {
            connection.createStatement().execute(
                    "CREATE TABLE accounts (id INT PRIMARY KEY, owner TEXT NOT NULL, balance BIGINT NOT NULL);");

            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO accounts VALUES (?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "Ada");
                insert.setLong(3, 50L);
                insert.executeUpdate();
                var savepoint = connection.setSavepoint();
                insert.setInt(1, 2);
                insert.setString(2, "Grace");
                insert.setLong(3, 75L);
                insert.executeUpdate();
                connection.rollback(savepoint);
            }
            connection.commit();

            try (ResultSet schemas = connection.getMetaData().getSchemas()) {
                assertTrue(schemas.next());
                assertEquals("public", schemas.getString("TABLE_SCHEM"));
            }

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT owner, balance FROM accounts WHERE id = ?")) {
                var metaData = query.getMetaData();
                var parameterMetaData = query.getParameterMetaData();
                assertEquals(2, metaData.getColumnCount());
                assertEquals("owner", metaData.getColumnLabel(1));
                assertEquals("balance", metaData.getColumnLabel(2));
                assertEquals(1, parameterMetaData.getParameterCount());
                assertEquals(java.sql.Types.INTEGER, parameterMetaData.getParameterType(1));
                query.setInt(1, 1);
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("Ada", resultSet.getString(1));
                    assertEquals(50L, resultSet.getLong(2));
                }
            }

            assertTrue(connection.getMetaData().supportsNamedParameters());
            assertTrue(connection.getMetaData().supportsMultipleResultSets());
            assertTrue(connection.getMetaData().supportsMultipleOpenResults());

            assertTrue(connection.isValid(1));
        }
    }

    @Test
    void remoteDriverSupportsOptionalAuthentication() throws Exception {
        String baseUrl;
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0, "app", "secret")) {
            baseUrl = "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port();

            Properties properties = new Properties();
            properties.setProperty("user", "app");
            properties.setProperty("password", "secret");
            try (Connection connection = DriverManager.getConnection(baseUrl, properties)) {
                assertTrue(connection.isValid(1));
            }

            Properties bad = new Properties();
            bad.setProperty("user", "app");
            bad.setProperty("password", "wrong");
            assertInstanceOf(SQLInvalidAuthorizationSpecException.class,
                    assertThrows(SQLInvalidAuthorizationSpecException.class,
                            () -> DriverManager.getConnection(baseUrl, bad)));
        }
    }

    @Test
    void remoteDriverSupportsUpdatableSingleTableResultSets() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0);
             Connection connection = DriverManager.getConnection(
                     "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE inventory (id INT PRIMARY KEY, name TEXT NOT NULL, qty INT NOT NULL);");
                statement.execute("INSERT INTO inventory VALUES (1, 'one', 10), (2, 'two', 20);");
            }

            try (Statement statement = connection.createStatement(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
                 ResultSet resultSet = statement.executeQuery("SELECT id, name, qty FROM inventory ORDER BY id")) {
                assertEquals(ResultSet.CONCUR_UPDATABLE, resultSet.getConcurrency());
                assertTrue(resultSet.next());
                resultSet.updateString("name", "uno");
                resultSet.updateRow();
                assertTrue(resultSet.next());
                resultSet.deleteRow();
            }

            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT id, name, qty FROM inventory WHERE id = ?",
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
                preparedStatement.setInt(1, 1);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(ResultSet.CONCUR_UPDATABLE, resultSet.getConcurrency());
                    resultSet.updateInt("qty", 88);
                    resultSet.updateRow();
                    resultSet.refreshRow();
                    assertEquals(88, resultSet.getInt("qty"));
                }
            }

            try (Statement verify = connection.createStatement();
                 ResultSet resultSet = verify.executeQuery("SELECT id, name, qty FROM inventory ORDER BY id")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
                assertEquals("uno", resultSet.getString(2));
                assertEquals(88, resultSet.getInt(3));
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void remoteDriverSupportsGeneratedKeyRequestsAndConnectionProperties() throws Exception {
        Executor directExecutor = Runnable::run;
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0)) {
            Properties properties = new Properties();
            properties.setProperty("clientName", "jdbc-remote-test");
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port(),
                    properties)) {
                connection.setClientInfo("ApplicationName", "remote-jdbc");
                assertEquals("remote-jdbc", connection.getClientInfo("ApplicationName"));

                connection.setNetworkTimeout(directExecutor, 4321);
                assertEquals(4321, connection.getNetworkTimeout());

                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE logs (" +
                            "id BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1), " +
                            "message TEXT NOT NULL);");
                    assertEquals(1, statement.executeUpdate(
                            "INSERT INTO logs (message) VALUES ('hello remote')",
                            Statement.RETURN_GENERATED_KEYS));
                    try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                        assertTrue(generatedKeys.next());
                        assertEquals(1L, generatedKeys.getLong(1));
                        assertFalse(generatedKeys.next());
                    }
                }

                try (PreparedStatement preparedStatement = connection.prepareStatement(
                        "SELECT message FROM logs WHERE id = ?", Statement.RETURN_GENERATED_KEYS)) {
                    preparedStatement.setLong(1, 1L);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        assertTrue(resultSet.next());
                        assertEquals("hello remote", resultSet.getString(1));
                    }
                }
            }
        }
    }

    @Test
    void remoteDriverSupportsCallableStatements() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0);
             Connection connection = DriverManager.getConnection(
                     "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE jobs (id INT PRIMARY KEY, status TEXT NOT NULL);");
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

            try (ResultSet procedureColumns = connection.getMetaData()
                    .getProcedureColumns(null, "public", "finish_job", "%")) {
                while (procedureColumns.next()) {
                    if ("p_total".equals(procedureColumns.getString("COLUMN_NAME"))) {
                        assertEquals(12, procedureColumns.getInt("PRECISION"));
                        assertEquals(2, procedureColumns.getInt("SCALE"));
                    }
                }
            }
            try (ResultSet functionColumns = connection.getMetaData()
                    .getFunctionColumns(null, "public", "add_fee", "%")) {
                while (functionColumns.next()) {
                    if ("RETURN_VALUE".equals(functionColumns.getString("COLUMN_NAME"))
                            || "p_amount".equals(functionColumns.getString("COLUMN_NAME"))) {
                        assertEquals(12, functionColumns.getInt("PRECISION"));
                        assertEquals(2, functionColumns.getInt("SCALE"));
                    }
                }
            }
        }
    }

    @Test
    void remoteDriverCanCancelLongRunningStatements() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0);
             Connection connection = DriverManager.getConnection(
                     "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE slow_rows (id INT PRIMARY KEY, note TEXT NOT NULL);");
            }
            seedRows(connection, "slow_rows", 5_000);

            boolean cancelledObserved = false;
            for (int attempt = 0; attempt < 5 && !cancelledObserved; attempt++) {
                try (Statement statement = connection.createStatement()) {
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    Thread worker = Thread.ofVirtual().start(() -> {
                        try (ResultSet ignored = statement.executeQuery("""
                                SELECT id
                                FROM slow_rows
                                ORDER BY REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                                             REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(note, 'x', 'y'), 'y', 'z'), 'z', 'q'), 'q', 'w'), 'w', 'e'),
                                             'e', 'r'), 'r', 't'), 't', 'u'), 'u', 'i'), 'i', 'o') DESC, id DESC
                                """)) {
                            while (ignored.next()) {
                                // Drain if the query escapes cancellation.
                            }
                        } catch (Throwable throwable) {
                            failure.set(throwable);
                        }
                    });

                    Thread.sleep(1L);
                    statement.cancel();
                    worker.join();

                    Throwable throwable = failure.get();
                    if (throwable == null) {
                        continue;
                    }
                    java.sql.SQLException cancelled = assertInstanceOf(java.sql.SQLException.class, throwable);
                    assertEquals("57014", cancelled.getSQLState());
                    cancelledObserved = true;
                }
            }
            assertTrue(cancelledObserved, "Expected at least one remote query cancellation to be observed");

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM slow_rows")) {
                assertTrue(resultSet.next());
                assertEquals(5_000L, resultSet.getLong(1));
            }
        }
    }

    private void seedRows(Connection connection, String tableName, int rowCount) throws Exception {
        int batchSize = 100;
        try (Statement statement = connection.createStatement()) {
            for (int start = 1; start <= rowCount; start += batchSize) {
                int end = Math.min(rowCount, start + batchSize - 1);
                StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" VALUES ");
                for (int id = start; id <= end; id++) {
                    if (id > start) {
                        sql.append(", ");
                    }
                    sql.append("(")
                            .append(id)
                            .append(", 'row-")
                            .append(id)
                            .append("-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                            .append("')");
                }
                statement.execute(sql.toString());
            }
        }
    }
}
