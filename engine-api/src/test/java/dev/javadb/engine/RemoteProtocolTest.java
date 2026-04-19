package dev.javadb.engine;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteProtocolTest {
    @Test
    void roundTripsAllMessageTypesWithTypedResultsAndNormalization() throws Exception {
        EngineApi.BatchResult batchResult = new EngineApi.BatchResult(List.of(
                new EngineApi.StatementResult("SELECT", 0,
                        new Common.TupleBatch(
                                List.of(
                                        new Common.ResultColumn("id", Common.DataType.INTEGER),
                                        new Common.ResultColumn("name", Common.DataType.TEXT),
                                        new Common.ResultColumn("enabled", Common.DataType.BOOLEAN),
                                        new Common.ResultColumn("big_total", Common.DataType.BIGINT)
                                ),
                                List.of(
                                        new Common.ResultRow(List.of(
                                                Common.Value.integer(1),
                                                Common.Value.text("Ada"),
                                                Common.Value.bool(true),
                                                Common.Value.bigint(99_999_999L)
                                        )),
                                        new Common.ResultRow(List.of(
                                                Common.Value.integer(2),
                                                Common.Value.nullValue(Common.DataType.TEXT),
                                                Common.Value.bool(false),
                                                Common.Value.nullValue(Common.DataType.BIGINT)
                                        ))
                                )),
                        new Common.TupleBatch(
                                List.of(new Common.ResultColumn("id", Common.DataType.BIGINT)),
                                List.of(new Common.ResultRow(List.of(Common.Value.bigint(101L))))),
                        "IndexLookup(table=users)")
        ));

        List<RemoteProtocol.Message> messages = List.of(
                new RemoteProtocol.ClientHello("   ", RemoteProtocol.VERSION, "app", "secret"),
                new RemoteProtocol.ServerHello("  javadb-server  ", RemoteProtocol.VERSION,
                        RemoteProtocol.DEFAULT_MAX_FRAME_BYTES, "session-1"),
                new RemoteProtocol.ExecuteRequest(7, "SELECT * FROM users WHERE id = 1", 500L),
                new RemoteProtocol.ExecuteResult(8, batchResult),
                new RemoteProtocol.PrepareRequest(14, "SELECT amount FROM ledger WHERE id = ?"),
                new RemoteProtocol.PrepareResult(15, new EngineApi.PreparedStatementDescription(
                        3L, "SELECT amount FROM ledger WHERE id = ?", 1,
                        List.of(new EngineApi.ParameterDescription(1, Common.DataType.INTEGER, 10, 0, true)),
                        List.of(new Common.ResultColumn("amount", Common.DataType.DECIMAL, 12, 2)))),
                new RemoteProtocol.ExecutePreparedRequest(16, 3L, List.of("42"), 1_000L),
                new RemoteProtocol.ClosePreparedRequest(17, 3L),
                new RemoteProtocol.ClosePreparedResult(18),
                new RemoteProtocol.CancelRequest(19, "session-1", 16L),
                new RemoteProtocol.CancelResult(20, true),
                new RemoteProtocol.TransactionRequest(10, "begin", "REPEATABLE READ"),
                new RemoteProtocol.TransactionResult(11, true),
                new RemoteProtocol.MetadataRequest(12, "tables", List.of("public", "users", "TABLE")),
                new RemoteProtocol.MetadataResult(13, new Common.TupleBatch(
                        List.of(new Common.ResultColumn("TABLE_NAME", Common.DataType.TEXT)),
                        List.of(new Common.ResultRow(List.of(Common.Value.text("users")))))),
                new RemoteProtocol.Failure(9, "  PARSE_ERROR  ", null, null, false),
                RemoteProtocol.Failure.protocolError("bad frame", true),
                new RemoteProtocol.CloseRequest(null),
                new RemoteProtocol.Goodbye(null)
        );

        for (RemoteProtocol.Message message : messages) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            RemoteProtocol.write(output, message);
            RemoteProtocol.Message decoded = RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray()));

            if (message instanceof RemoteProtocol.ClientHello) {
                assertEquals(new RemoteProtocol.ClientHello("unknown", RemoteProtocol.VERSION, "app", "secret"), decoded);
            } else if (message instanceof RemoteProtocol.ServerHello) {
                assertEquals(new RemoteProtocol.ServerHello("javadb-server", RemoteProtocol.VERSION,
                        RemoteProtocol.DEFAULT_MAX_FRAME_BYTES, "session-1"), decoded);
            } else if (message instanceof RemoteProtocol.TransactionRequest) {
                assertEquals(new RemoteProtocol.TransactionRequest(10, "BEGIN", "REPEATABLE READ"), decoded);
            } else if (message instanceof RemoteProtocol.MetadataRequest) {
                assertEquals(new RemoteProtocol.MetadataRequest(12, "TABLES", List.of("public", "users", "TABLE")), decoded);
            } else if (message instanceof RemoteProtocol.Failure failure && failure.requestId() == 9) {
                assertEquals(new RemoteProtocol.Failure(9, "PARSE_ERROR", "", Common.SourceSpan.NONE, false), decoded);
            } else if (message instanceof RemoteProtocol.Goodbye) {
                assertEquals(new RemoteProtocol.Goodbye(""), decoded);
            } else if (message instanceof RemoteProtocol.CloseRequest) {
                assertEquals(new RemoteProtocol.CloseRequest(""), decoded);
            } else {
                assertEquals(message, decoded);
            }
        }
    }

    @Test
    void readReturnsNullAtEndOfStreamAndRejectsInvalidFraming() throws Exception {
        assertNull(RemoteProtocol.read(new ByteArrayInputStream(new byte[0])));

        RemoteProtocol.ProtocolException badMagic = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(0x01020304, RemoteProtocol.VERSION, 18, new byte[0]))));
        RemoteProtocol.ProtocolException badVersion = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, (short) 99, 18, new byte[0]))));
        RemoteProtocol.ProtocolException badType = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 99, new byte[0]))));
        RemoteProtocol.ProtocolException badLength = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 18, -1, new byte[0]))));

        assertAll(
                () -> assertTrue(badMagic.getMessage().contains("Invalid protocol magic")),
                () -> assertTrue(badVersion.getMessage().contains("Unsupported frame version")),
                () -> assertTrue(badType.getMessage().contains("Unknown message type")),
                () -> assertTrue(badLength.getMessage().contains("Invalid payload length"))
        );
    }

    @Test
    void rejectsOversizedFramesTrailingBytesAndMalformedPayloadData() throws Exception {
        ByteArrayOutputStream tooLarge = new ByteArrayOutputStream();
        RemoteProtocol.ProtocolException oversized = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.write(tooLarge,
                        new RemoteProtocol.ExecuteRequest(1, "x".repeat(256), 0),
                        32));

        byte[] goodbyePayload = encodedString("bye");
        byte[] trailingPayload = ByteBuffer.allocate(goodbyePayload.length + Integer.BYTES)
                .put(goodbyePayload)
                .putInt(1)
                .array();
        RemoteProtocol.ProtocolException trailing = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 18, trailingPayload))));

        byte[] truncatedString = ByteBuffer.allocate(Integer.BYTES + 2)
                .putInt(5)
                .put(new byte[]{'o', 'k'})
                .array();
        EOFException truncated = assertThrows(EOFException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 18, truncatedString))));

        byte[] negativeCountPayload = ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(4L)
                .putInt(-1)
                .array();
        RemoteProtocol.ProtocolException negativeCount = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 4, negativeCountPayload))));

        ByteArrayOutputStream invalidTypePayload = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(invalidTypePayload)) {
            output.writeLong(5L);
            output.writeInt(1);
            writeString(output, "SELECT");
            output.writeLong(0L);
            writeString(output, "");
            output.writeInt(1);
            writeString(output, "broken");
            output.writeByte(255);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
        }
        RemoteProtocol.ProtocolException invalidType = assertThrows(RemoteProtocol.ProtocolException.class,
                () -> RemoteProtocol.read(new ByteArrayInputStream(rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 4,
                        invalidTypePayload.toByteArray()))));

        assertAll(
                () -> assertTrue(oversized.getMessage().contains("Frame exceeds limit")),
                () -> assertTrue(trailing.getMessage().contains("Trailing bytes")),
                () -> assertTrue(truncated.getMessage().contains("Unexpected end of stream while reading string")),
                () -> assertTrue(negativeCount.getMessage().contains("Negative count")),
                () -> assertTrue(invalidType.getMessage().contains("Invalid data type ordinal"))
        );
    }

    @Test
    void validatesConstructorArgumentsAndFrameLimits() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new RemoteProtocol.ExecuteRequest(-1, "SELECT 1", 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RemoteProtocol.ExecuteResult(-1, new EngineApi.BatchResult(List.of()))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RemoteProtocol.Failure(-2, "BAD", "bad", Common.SourceSpan.NONE, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RemoteProtocol.ServerHello("server", RemoteProtocol.VERSION, 0, "session")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RemoteProtocol.read(new ByteArrayInputStream(new byte[0]), 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RemoteProtocol.write(new ByteArrayOutputStream(), new RemoteProtocol.Goodbye("bye"), 0))
        );
    }

    @Test
    void readsLegacyClientHelloWithoutCredentialPayload() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payload)) {
            writeString(output, "legacy-client");
            output.writeShort(RemoteProtocol.VERSION);
        }
        RemoteProtocol.ClientHello hello = assertInstanceOf(RemoteProtocol.ClientHello.class,
                RemoteProtocol.read(new ByteArrayInputStream(
                        rawFrame(RemoteProtocol.MAGIC, RemoteProtocol.VERSION, 1, payload.toByteArray()))));
        assertEquals("legacy-client", hello.clientName());
        assertEquals("", hello.user());
        assertEquals("", hello.password());
    }

    @Test
    void roundTripsDecimalAndTemporalValuesAcrossExecuteResults() throws Exception {
        EngineApi.BatchResult batchResult = new EngineApi.BatchResult(List.of(
                new EngineApi.StatementResult("SELECT", 0,
                        new Common.TupleBatch(
                                List.of(
                                        new Common.ResultColumn("amount", Common.DataType.DECIMAL),
                                        new Common.ResultColumn("booked_on", Common.DataType.DATE),
                                        new Common.ResultColumn("booked_time", Common.DataType.TIME),
                                        new Common.ResultColumn("booked_at", Common.DataType.TIMESTAMP)
                                ),
                                List.of(new Common.ResultRow(List.of(
                                        Common.Value.decimal(new BigDecimal("12.50")),
                                        Common.Value.date(LocalDate.parse("2026-04-19")),
                                        Common.Value.time(LocalTime.parse("10:15:30")),
                                        Common.Value.timestamp(LocalDateTime.parse("2026-04-19T10:15:30"))
                                )))),
                        Common.TupleBatch.empty(),
                        "TableScan(table=ledger)")
        ));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RemoteProtocol.write(output, new RemoteProtocol.ExecuteResult(8, batchResult));
        RemoteProtocol.ExecuteResult decoded = assertInstanceOf(RemoteProtocol.ExecuteResult.class,
                RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray())));

        Common.ResultRow row = decoded.result().statements().getFirst().batch().rows().getFirst();
        assertEquals(0, new BigDecimal("12.50").compareTo(row.get(0).asDecimal()));
        assertEquals(LocalDate.parse("2026-04-19"), row.get(1).asDate());
        assertEquals(LocalTime.parse("10:15:30"), row.get(2).asTime());
        assertEquals(LocalDateTime.parse("2026-04-19T10:15:30"), row.get(3).asTimestamp());
    }

    private byte[] rawFrame(int magic, short version, int typeCode, byte[] payload) throws Exception {
        return rawFrame(magic, version, typeCode, payload.length, payload);
    }

    private byte[] rawFrame(int magic, short version, int typeCode, int declaredLength, byte[] payload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(magic);
            data.writeShort(version);
            data.writeByte(typeCode);
            data.writeInt(declaredLength);
            data.write(payload);
        }
        return output.toByteArray();
    }

    private byte[] encodedString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + bytes.length)
                .putInt(bytes.length)
                .put(bytes)
                .array();
    }

    private void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
