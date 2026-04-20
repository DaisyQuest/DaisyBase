package dev.daisybase.jdbc;

import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.server.DaisyBaseServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.RowId;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcObjectWrapperTest {
    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName(DaisyBaseDriver.class.getName());
    }

    @TempDir
    Path tempDir;

    @Test
    void embeddedConnectionSupportsDisconnectedJdbcObjectsAndTextBackedLobs() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:daisybase:embedded:" + tempDir)) {
            Clob clob = connection.createClob();
            clob.setString(1L, "hello");
            try (Writer writer = clob.setCharacterStream(6L)) {
                writer.write(" world");
            }
            assertEquals("hello world", readClob(clob));

            NClob nClob = connection.createNClob();
            nClob.setString(1L, "multilingual");
            assertEquals("multilingual", readClob(nClob));

            SQLXML sqlxml = connection.createSQLXML();
            try (Writer writer = sqlxml.setCharacterStream()) {
                writer.write("<doc>one</doc>");
            }
            assertEquals("<doc>one</doc>", sqlxml.getString());

            Blob blob = connection.createBlob();
            blob.setBytes(1L, new byte[]{1, 2, 3});
            assertArrayEquals(new byte[]{1, 2, 3}, blob.getBytes(1L, 3));

            Array array = connection.createArrayOf("INTEGER", new Object[]{1, 2, 3});
            assertEquals("INTEGER", array.getBaseTypeName());
            assertEquals(Types.INTEGER, array.getBaseType());
            assertArrayEquals(new Object[]{1, 2, 3}, (Object[]) array.getArray());

            Struct struct = connection.createStruct("audit_row", new Object[]{1, "ok"});
            assertEquals("audit_row", struct.getSQLTypeName());
            assertArrayEquals(new Object[]{1, "ok"}, struct.getAttributes());

            Ref ref = new DaisyBaseRef("audit_row", 42);
            assertEquals("audit_row", ref.getBaseTypeName());
            assertEquals(42, ref.getObject());

            RowId rowId = new DaisyBaseRowId(new byte[]{7, 8, 9});
            assertArrayEquals(new byte[]{7, 8, 9}, rowId.getBytes());

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE docs (
                            id INT PRIMARY KEY,
                            body TEXT NOT NULL,
                            note TEXT NOT NULL,
                            xml_payload TEXT NOT NULL,
                            blob_payload TEXT NOT NULL,
                            array_payload TEXT NOT NULL,
                            struct_payload TEXT NOT NULL,
                            ref_payload TEXT NOT NULL,
                            rowid_payload TEXT NOT NULL
                        );
                        """);
                statement.execute("""
                        CREATE FUNCTION public.echo_text(p_text TEXT) RETURN TEXT IS
                        BEGIN
                          RETURN p_text;
                        END;
                        """);
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO docs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setClob(2, clob);
                insert.setNClob(3, nClob);
                insert.setSQLXML(4, sqlxml);
                insert.setBlob(5, blob);
                insert.setArray(6, array);
                insert.setObject(7, struct);
                insert.setRef(8, ref);
                insert.setRowId(9, rowId);
                assertEquals(1, insert.executeUpdate());
            }

            Clob objectClob = connection.createClob();
            objectClob.setString(1L, "object body");
            NClob objectNClob = connection.createNClob();
            objectNClob.setString(1L, "object note");
            SQLXML objectSqlXml = connection.createSQLXML();
            objectSqlXml.setString("<doc>two</doc>");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO docs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setInt(1, 2);
                insert.setObject(2, objectClob);
                insert.setObject(3, objectNClob);
                insert.setObject(4, objectSqlXml);
                insert.setBytes(5, new byte[]{4, 5, 6});
                insert.setObject(6, connection.createArrayOf("TEXT", new Object[]{"a", "b"}));
                insert.setObject(7, connection.createStruct("audit_row", new Object[]{2, "two"}));
                insert.setObject(8, new DaisyBaseRef("audit_row", 84));
                insert.setObject(9, new DaisyBaseRowId(new byte[]{4, 2}));
                assertEquals(1, insert.executeUpdate());
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO docs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setInt(1, 3);
                insert.setString(2, "stream body");
                insert.setString(3, "stream note");
                insert.setString(4, "<doc>stream</doc>");
                insert.setBinaryStream(5, new java.io.ByteArrayInputStream(new byte[]{9, 10}));
                insert.setArray(6, connection.createArrayOf("BIGINT", new Object[]{9L, 10L}));
                insert.setObject(7, connection.createStruct("audit_row", new Object[]{3, "three"}));
                insert.setRef(8, new DaisyBaseRef("audit_row", 126));
                insert.setRowId(9, new DaisyBaseRowId(new byte[]{1, 2, 3}));
                assertEquals(1, insert.executeUpdate());
            }

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT body, note, xml_payload, blob_payload, array_payload, struct_payload, ref_payload, rowid_payload FROM docs WHERE id = ?")) {
                query.setInt(1, 1);
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("hello world", readClob(resultSet.getClob(1)));
                    assertEquals("multilingual", readClob(resultSet.getNClob(2)));
                    assertEquals("<doc>one</doc>", resultSet.getSQLXML(3).getString());
                    assertEquals("hello world", readAll(resultSet.getCharacterStream(1)));
                    assertEquals("multilingual", readAll(resultSet.getNCharacterStream(2)));
                    assertEquals("hello world", readClob(resultSet.getObject(1, Clob.class)));
                    assertEquals("multilingual", readClob(resultSet.getObject(2, NClob.class)));
                    assertEquals("<doc>one</doc>", resultSet.getObject(3, SQLXML.class).getString());
                    assertArrayEquals(new byte[]{1, 2, 3}, resultSet.getBytes(4));
                    assertArrayEquals(new byte[]{1, 2, 3}, resultSet.getBlob(4).getBytes(1L, 3));
                    assertArrayEquals(new Object[]{1, 2, 3}, (Object[]) resultSet.getArray(5).getArray());
                    assertArrayEquals(new Object[]{1, 2, 3}, (Object[]) resultSet.getObject(5, Array.class).getArray());
                    Struct structValue = resultSet.getObject(6, Struct.class);
                    assertEquals("audit_row", structValue.getSQLTypeName());
                    assertArrayEquals(new Object[]{1, "ok"}, structValue.getAttributes());
                    assertEquals(42, resultSet.getRef(7).getObject());
                    assertArrayEquals(new byte[]{7, 8, 9}, resultSet.getRowId(8).getBytes());
                    assertArrayEquals(new byte[]{1, 2, 3}, resultSet.getObject(4, Blob.class).getBytes(1L, 3));
                }
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                SQLXML input = connection.createSQLXML();
                input.setString("<echo>call</echo>");
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setSQLXML(2, input);
                assertFalse(callable.execute());
                assertEquals("<echo>call</echo>", readClob(callable.getClob(1)));
                assertEquals("<echo>call</echo>", readClob(callable.getNClob(1)));
                assertEquals("<echo>call</echo>", callable.getSQLXML(1).getString());
                assertEquals("<echo>call</echo>", readAll(callable.getCharacterStream(1)));
                assertEquals("<echo>call</echo>", readAll(callable.getNCharacterStream(1)));
                assertEquals("<echo>call</echo>", readClob(callable.getObject(1, Clob.class)));
                assertEquals("<echo>call</echo>", callable.getObject(1, SQLXML.class).getString());
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setBlob(2, blob);
                assertFalse(callable.execute());
                assertArrayEquals(new byte[]{1, 2, 3}, callable.getBytes(1));
                assertArrayEquals(new byte[]{1, 2, 3}, callable.getBlob(1).getBytes(1L, 3));
                assertArrayEquals(new byte[]{1, 2, 3}, callable.getObject(1, Blob.class).getBytes(1L, 3));
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setArray(2, array);
                assertFalse(callable.execute());
                assertArrayEquals(new Object[]{1, 2, 3}, (Object[]) callable.getArray(1).getArray());
                assertArrayEquals(new Object[]{1, 2, 3}, (Object[]) callable.getObject(1, Array.class).getArray());
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setObject(2, struct);
                assertFalse(callable.execute());
                Struct echoedStruct = callable.getObject(1, Struct.class);
                assertEquals("audit_row", echoedStruct.getSQLTypeName());
                assertArrayEquals(new Object[]{1, "ok"}, echoedStruct.getAttributes());
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setRef(2, ref);
                assertFalse(callable.execute());
                assertEquals(42, callable.getRef(1).getObject());
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setRowId(2, rowId);
                assertFalse(callable.execute());
                assertArrayEquals(new byte[]{7, 8, 9}, callable.getRowId(1).getBytes());
            }
        }
    }

    @Test
    void remoteConnectionSupportsTextBackedLobAccessors() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DaisyBaseServer server = DaisyBaseServer.start(engine, 0);
             Connection connection = DriverManager.getConnection(
                     "jdbc:daisybase:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE docs (
                            id INT PRIMARY KEY,
                            body TEXT NOT NULL,
                            xml_payload TEXT NOT NULL,
                            blob_payload TEXT NOT NULL
                        );
                        """);
                statement.execute("""
                        CREATE FUNCTION public.echo_text(p_text TEXT) RETURN TEXT IS
                        BEGIN
                          RETURN p_text;
                        END;
                        """);
            }

            Clob body = connection.createClob();
            body.setString(1L, "remote body");
            SQLXML payload = connection.createSQLXML();
            payload.setString("<remote>payload</remote>");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO docs VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setClob(2, body);
                insert.setSQLXML(3, payload);
                insert.setBytes(4, new byte[]{11, 12});
                assertEquals(1, insert.executeUpdate());
            }

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT body, xml_payload, blob_payload FROM docs WHERE id = ?")) {
                query.setInt(1, 1);
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("remote body", readClob(resultSet.getClob(1)));
                    assertEquals("<remote>payload</remote>", resultSet.getSQLXML(2).getString());
                    assertEquals("remote body", readClob(resultSet.getObject(1, Clob.class)));
                    assertEquals("<remote>payload</remote>", resultSet.getObject(2, SQLXML.class).getString());
                    assertArrayEquals(new byte[]{11, 12}, resultSet.getBlob(3).getBytes(1L, 2));
                }
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                callable.setString(2, "<remote>call</remote>");
                assertFalse(callable.execute());
                assertEquals("<remote>call</remote>", readClob(callable.getClob(1)));
                assertEquals("<remote>call</remote>", callable.getSQLXML(1).getString());
            }

            try (CallableStatement callable = connection.prepareCall("{? = call public.echo_text(?)}")) {
                callable.registerOutParameter(1, Types.VARCHAR);
                Blob blob = connection.createBlob();
                blob.setBytes(1L, new byte[]{20, 21});
                callable.setBlob(2, blob);
                assertFalse(callable.execute());
                assertArrayEquals(new byte[]{20, 21}, callable.getBlob(1).getBytes(1L, 2));
            }
        }
    }

    private static String readClob(Clob clob) throws Exception {
        return clob == null ? null : clob.getSubString(1L, (int) clob.length());
    }

    private static String readAll(Reader reader) throws Exception {
        if (reader == null) {
            return null;
        }
        try (Reader closeable = reader) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[256];
            int read;
            while ((read = closeable.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }
}
