package dev.daisybase.server;

import dev.daisybase.common.Common;
import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.engine.RemoteProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseProtocolServerTest {
    @TempDir
    Path tempDir;

    @Test
    void handshakeNegotiatesVersionAndCloseRequestReturnsGoodbye() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
            RemoteProtocol.ServerHello hello = client.handshake(RemoteProtocol.VERSION);

            assertEquals("daisybase-server", hello.serverName());
            assertEquals(RemoteProtocol.VERSION, hello.protocolVersion());
            assertEquals(RemoteProtocol.DEFAULT_MAX_FRAME_BYTES, hello.maxFrameBytes());
            assertFalse(hello.sessionToken().isBlank());

            client.send(new RemoteProtocol.CloseRequest(""));
            RemoteProtocol.Goodbye goodbye = assertInstanceOf(RemoteProtocol.Goodbye.class, client.receive());
            assertEquals("bye", goodbye.message());
        }
    }

    @Test
    void executeRequestReturnsTypedResults() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
            client.handshake(RemoteProtocol.VERSION);

            client.expectSuccess(1, "CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL);");
            RemoteProtocol.ExecuteResult insert = client.expectSuccess(2,
                    "INSERT INTO users VALUES (1, 'Ada'), (2, 'Grace');");
            assertEquals(2L, insert.result().statements().getFirst().updateCount());

            RemoteProtocol.ExecuteResult select = client.expectSuccess(3,
                    "SELECT id, name FROM users ORDER BY id;");

            EngineApi.StatementResult statement = select.result().statements().getFirst();
            assertEquals(2, statement.batch().columns().size());
            assertEquals(Common.DataType.INTEGER, statement.batch().columns().get(0).type());
            assertEquals(Common.DataType.TEXT, statement.batch().columns().get(1).type());
            assertEquals(2, statement.batch().rows().size());
            assertEquals(1, statement.batch().rows().get(0).get(0).asInt());
            assertEquals("Ada", statement.batch().rows().get(0).get(1).asText());
            assertEquals(2, statement.batch().rows().get(1).get(0).asInt());
            assertEquals("Grace", statement.batch().rows().get(1).get(1).asText());
        }
    }

    @Test
    void statementFailureIsStructuredAndConnectionStaysUsable() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
            client.handshake(RemoteProtocol.VERSION);

            client.expectSuccess(1, "CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL);");
            client.expectSuccess(2, "INSERT INTO users VALUES (1, 'Ada');");

            client.send(new RemoteProtocol.ExecuteRequest(3, "SELECT 'Ada", 0));
            RemoteProtocol.Failure failure = assertInstanceOf(RemoteProtocol.Failure.class, client.receive());
            assertEquals(3L, failure.requestId());
            assertEquals("PARSE_ERROR", failure.code());
            assertFalse(failure.fatal());
            assertTrue(failure.message().contains("Unterminated string literal"));

            RemoteProtocol.ExecuteResult count = client.expectSuccess(4, "SELECT COUNT(*) AS total FROM users;");
            assertEquals(1L, count.result().statements().getFirst().batch().rows().getFirst().get(0).asLong());
        }
    }

    @Test
    void mismatchedProtocolVersionReturnsFatalFailure() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
            client.send(new RemoteProtocol.ClientHello("junit", (short) (RemoteProtocol.VERSION + 1), "", ""));

            RemoteProtocol.Failure failure = assertInstanceOf(RemoteProtocol.Failure.class, client.receive());
            assertEquals("PROTOCOL_ERROR", failure.code());
            assertTrue(failure.fatal());
            assertTrue(failure.message().contains("Unsupported protocol version"));
        }
    }

    @Test
    void handshakeRejectsInvalidCredentials() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE USER app IDENTIFIED BY 'secret';");
            try (DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
                client.send(new RemoteProtocol.ClientHello("junit", RemoteProtocol.VERSION, "app", "wrong"));

                RemoteProtocol.Failure failure = assertInstanceOf(RemoteProtocol.Failure.class, client.receive());
                assertEquals("AUTHENTICATION_FAILED", failure.code());
                assertTrue(failure.fatal());
            }
        }
    }

    @Test
    void transactionAndMetadataRequestsWorkAcrossProtocol() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
            client.handshake(RemoteProtocol.VERSION);

            RemoteProtocol.TransactionResult begin = client.expectTransaction("BEGIN", "READ COMMITTED");
            assertTrue(begin.active());
            client.expectSuccess(2, "CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL);");
            client.expectSuccess(3, "CREATE UNIQUE INDEX users_name_idx ON users (name);");
            RemoteProtocol.TransactionResult savepoint = client.expectTransaction("SAVEPOINT", "sp1");
            assertTrue(savepoint.active());
            client.expectSuccess(4, "INSERT INTO users VALUES (1, 'Ada');");
            RemoteProtocol.TransactionResult rollbackToSavepoint = client.expectTransaction("ROLLBACK_TO_SAVEPOINT", "sp1");
            assertTrue(rollbackToSavepoint.active());
            RemoteProtocol.TransactionResult commit = client.expectTransaction("COMMIT", "");
            assertFalse(commit.active());

            client.expectSuccess(5, "INSERT INTO users VALUES (2, 'Grace');");
            RemoteProtocol.MetadataResult tables = client.expectMetadata("TABLES", "public", "users", "TABLE");
            assertEquals("users", tables.batch().rows().getFirst().get(2).asText());
            RemoteProtocol.MetadataResult indexes = client.expectMetadata("INDEX_INFO", "public", "users", "false");
            assertEquals("users_name_idx", indexes.batch().rows().getFirst().get(5).asText());
        }
    }

    @Test
    void prepareRequestDescribesResultColumns() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DatabaseProtocolServer server = DatabaseProtocolServer.start(engine, 0);
             ProtocolClient client = ProtocolClient.connect(server.port())) {
            client.handshake(RemoteProtocol.VERSION);
            client.expectSuccess(1, "CREATE TABLE metrics (id INT PRIMARY KEY, amount DECIMAL(12, 2) NOT NULL);");

            RemoteProtocol.PrepareResult prepare = client.expectPrepare(2,
                    "SELECT amount FROM metrics WHERE id = ?");

            assertEquals(1, prepare.description().resultColumns().size());
            assertEquals("amount", prepare.description().resultColumns().getFirst().name());
            assertEquals(12, prepare.description().resultColumns().getFirst().precision());
            assertEquals(2, prepare.description().resultColumns().getFirst().scale());
        }
    }

    private static final class ProtocolClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private ProtocolClient(Socket socket) throws Exception {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }

        static ProtocolClient connect(int port) throws Exception {
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(2_000);
            return new ProtocolClient(socket);
        }

        RemoteProtocol.ServerHello handshake(short version) throws Exception {
            return handshake(version, "", "");
        }

        RemoteProtocol.ServerHello handshake(short version, String user, String password) throws Exception {
            send(new RemoteProtocol.ClientHello("junit", version, user, password));
            return assertInstanceOf(RemoteProtocol.ServerHello.class, receive());
        }

        RemoteProtocol.ExecuteResult expectSuccess(long requestId, String sql) throws Exception {
            send(new RemoteProtocol.ExecuteRequest(requestId, sql, 0));
            return assertInstanceOf(RemoteProtocol.ExecuteResult.class, receive());
        }

        RemoteProtocol.PrepareResult expectPrepare(long requestId, String sql) throws Exception {
            send(new RemoteProtocol.PrepareRequest(requestId, sql));
            return assertInstanceOf(RemoteProtocol.PrepareResult.class, receive());
        }

        RemoteProtocol.TransactionResult expectTransaction(String operation, String argument) throws Exception {
            send(new RemoteProtocol.TransactionRequest(90, operation, argument));
            return assertInstanceOf(RemoteProtocol.TransactionResult.class, receive());
        }

        RemoteProtocol.MetadataResult expectMetadata(String operation, String... arguments) throws Exception {
            send(new RemoteProtocol.MetadataRequest(91, operation, java.util.List.of(arguments)));
            return assertInstanceOf(RemoteProtocol.MetadataResult.class, receive());
        }

        void send(RemoteProtocol.Message message) throws Exception {
            RemoteProtocol.write(output, message);
        }

        RemoteProtocol.Message receive() throws Exception {
            return RemoteProtocol.read(input);
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
