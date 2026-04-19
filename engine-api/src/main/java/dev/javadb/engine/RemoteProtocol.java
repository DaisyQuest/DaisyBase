package dev.javadb.engine;

import dev.javadb.common.Common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RemoteProtocol {
    public static final int MAGIC = 0x4A444250;
    public static final short VERSION = 1;
    public static final int DEFAULT_MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private RemoteProtocol() {
    }

    public sealed interface Message permits ClientHello, ServerHello, ExecuteRequest, ExecuteResult,
            PrepareRequest, PrepareResult, ExecutePreparedRequest, ClosePreparedRequest, ClosePreparedResult,
            CancelRequest, CancelResult, TransactionRequest, TransactionResult, MetadataRequest, MetadataResult,
            Failure, CloseRequest, Goodbye {
    }

    public record ClientHello(String clientName, short protocolVersion, String user, String password) implements Message {
        public ClientHello {
            clientName = normalizeName(clientName);
            user = user == null ? "" : user;
            password = password == null ? "" : password;
        }
    }

    public record ServerHello(String serverName, short protocolVersion, int maxFrameBytes,
                              String sessionToken) implements Message {
        public ServerHello {
            serverName = normalizeName(serverName);
            maxFrameBytes = normalizeFrameLimit(maxFrameBytes);
            sessionToken = normalizeToken(sessionToken);
        }
    }

    public record ExecuteRequest(long requestId, String sql, long timeoutMillis) implements Message {
        public ExecuteRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            Objects.requireNonNull(sql, "sql");
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("timeoutMillis must be non-negative");
            }
        }
    }

    public record ExecuteResult(long requestId, EngineApi.BatchResult result) implements Message {
        public ExecuteResult {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            Objects.requireNonNull(result, "result");
        }
    }

    public record PrepareRequest(long requestId, String sql) implements Message {
        public PrepareRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            Objects.requireNonNull(sql, "sql");
        }
    }

    public record PrepareResult(long requestId, EngineApi.PreparedStatementDescription description) implements Message {
        public PrepareResult {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            Objects.requireNonNull(description, "description");
        }
    }

    public record ExecutePreparedRequest(long requestId, long statementId, List<String> parameterLiterals,
                                         long timeoutMillis) implements Message {
        public ExecutePreparedRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            if (statementId < 0) {
                throw new IllegalArgumentException("statementId must be non-negative");
            }
            parameterLiterals = parameterLiterals == null ? List.of() : parameterLiterals.stream()
                    .map(literal -> literal == null ? "NULL" : literal)
                    .toList();
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("timeoutMillis must be non-negative");
            }
        }
    }

    public record ClosePreparedRequest(long requestId, long statementId) implements Message {
        public ClosePreparedRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            if (statementId < 0) {
                throw new IllegalArgumentException("statementId must be non-negative");
            }
        }
    }

    public record ClosePreparedResult(long requestId) implements Message {
        public ClosePreparedResult {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
        }
    }

    public record CancelRequest(long requestId, String sessionToken, long executionId) implements Message {
        public CancelRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            sessionToken = normalizeToken(sessionToken);
            if (executionId < 0) {
                throw new IllegalArgumentException("executionId must be non-negative");
            }
        }
    }

    public record CancelResult(long requestId, boolean cancelled) implements Message {
        public CancelResult {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
        }
    }

    public record TransactionRequest(long requestId, String operation, String argument) implements Message {
        public TransactionRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            operation = normalizeName(operation).toUpperCase(Locale.ROOT);
            argument = argument == null ? "" : argument;
        }
    }

    public record TransactionResult(long requestId, boolean active) implements Message {
        public TransactionResult {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
        }
    }

    public record MetadataRequest(long requestId, String operation, List<String> arguments) implements Message {
        public MetadataRequest {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            operation = normalizeName(operation).toUpperCase(Locale.ROOT);
            arguments = arguments == null ? List.of() : arguments.stream()
                    .map(argument -> argument == null ? "" : argument)
                    .toList();
        }
    }

    public record MetadataResult(long requestId, Common.TupleBatch batch) implements Message {
        public MetadataResult {
            if (requestId < 0) {
                throw new IllegalArgumentException("requestId must be non-negative");
            }
            Objects.requireNonNull(batch, "batch");
        }
    }

    public record Failure(long requestId, String code, String message, Common.SourceSpan span, boolean fatal) implements Message {
        public Failure {
            if (requestId < -1) {
                throw new IllegalArgumentException("requestId must be >= -1");
            }
            code = normalizeName(code);
            message = message == null ? "" : message;
            span = span == null ? Common.SourceSpan.NONE : span;
        }

        public static Failure protocolError(String message, boolean fatal) {
            return new Failure(-1, "PROTOCOL_ERROR", message, Common.SourceSpan.NONE, fatal);
        }
    }

    public record CloseRequest(String reason) implements Message {
        public CloseRequest {
            reason = reason == null ? "" : reason;
        }
    }

    public record Goodbye(String message) implements Message {
        public Goodbye {
            message = message == null ? "" : message;
        }
    }

    public static void write(OutputStream outputStream, Message message) throws IOException {
        write(outputStream, message, DEFAULT_MAX_FRAME_BYTES);
    }

    public static void write(OutputStream outputStream, Message message, int maxFrameBytes) throws IOException {
        Objects.requireNonNull(outputStream, "outputStream");
        Objects.requireNonNull(message, "message");
        int frameLimit = normalizeFrameLimit(maxFrameBytes);
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
        try (DataOutputStream payload = new DataOutputStream(payloadBuffer)) {
            writePayload(payload, message);
            payload.flush();
        }
        byte[] payloadBytes = payloadBuffer.toByteArray();
        if (payloadBytes.length > frameLimit) {
            throw new ProtocolException("Frame exceeds limit of " + frameLimit + " bytes");
        }
        DataOutputStream frame = new DataOutputStream(outputStream);
        frame.writeInt(MAGIC);
        frame.writeShort(VERSION);
        frame.writeByte(MessageType.forMessage(message).code);
        frame.writeInt(payloadBytes.length);
        frame.write(payloadBytes);
        frame.flush();
    }

    public static Message read(InputStream inputStream) throws IOException {
        return read(inputStream, DEFAULT_MAX_FRAME_BYTES);
    }

    public static Message read(InputStream inputStream, int maxFrameBytes) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        int frameLimit = normalizeFrameLimit(maxFrameBytes);
        DataInputStream frame = new DataInputStream(inputStream);
        int magic;
        try {
            magic = frame.readInt();
        } catch (EOFException endOfStream) {
            return null;
        }
        short version = frame.readShort();
        if (magic != MAGIC) {
            throw new ProtocolException("Invalid protocol magic: " + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new ProtocolException("Unsupported frame version: " + version);
        }
        MessageType type = MessageType.fromCode(frame.readUnsignedByte());
        int payloadLength = frame.readInt();
        if (payloadLength < 0 || payloadLength > frameLimit) {
            throw new ProtocolException("Invalid payload length: " + payloadLength);
        }
        byte[] payloadBytes = frame.readNBytes(payloadLength);
        if (payloadBytes.length != payloadLength) {
            throw new EOFException("Unexpected end of stream while reading frame payload");
        }
        try (DataInputStream payload = new DataInputStream(new ByteArrayInputStream(payloadBytes))) {
            Message message = readPayload(payload, type);
            if (payload.available() != 0) {
                throw new ProtocolException("Trailing bytes detected in " + type + " frame");
            }
            return message;
        }
    }

    private static void writePayload(DataOutputStream output, Message message) throws IOException {
        switch (message) {
            case ClientHello hello -> {
                    writeString(output, hello.clientName());
                    output.writeShort(hello.protocolVersion());
                    writeString(output, hello.user());
                    writeString(output, hello.password());
                }
            case ServerHello hello -> {
                writeString(output, hello.serverName());
                output.writeShort(hello.protocolVersion());
                output.writeInt(hello.maxFrameBytes());
                writeString(output, hello.sessionToken());
            }
            case ExecuteRequest request -> {
                output.writeLong(request.requestId());
                writeString(output, request.sql());
                output.writeLong(request.timeoutMillis());
            }
            case ExecuteResult result -> {
                output.writeLong(result.requestId());
                writeBatchResult(output, result.result());
            }
            case PrepareRequest request -> {
                output.writeLong(request.requestId());
                writeString(output, request.sql());
            }
            case PrepareResult result -> {
                output.writeLong(result.requestId());
                writePreparedDescription(output, result.description());
            }
            case ExecutePreparedRequest request -> {
                output.writeLong(request.requestId());
                output.writeLong(request.statementId());
                writeStringList(output, request.parameterLiterals());
                output.writeLong(request.timeoutMillis());
            }
            case ClosePreparedRequest request -> {
                output.writeLong(request.requestId());
                output.writeLong(request.statementId());
            }
            case ClosePreparedResult result -> output.writeLong(result.requestId());
            case CancelRequest request -> {
                output.writeLong(request.requestId());
                writeString(output, request.sessionToken());
                output.writeLong(request.executionId());
            }
            case CancelResult result -> {
                output.writeLong(result.requestId());
                output.writeBoolean(result.cancelled());
            }
            case TransactionRequest request -> {
                output.writeLong(request.requestId());
                writeString(output, request.operation());
                writeString(output, request.argument());
            }
            case TransactionResult result -> {
                output.writeLong(result.requestId());
                output.writeBoolean(result.active());
            }
            case MetadataRequest request -> {
                output.writeLong(request.requestId());
                writeString(output, request.operation());
                writeStringList(output, request.arguments());
            }
            case MetadataResult result -> {
                output.writeLong(result.requestId());
                writeTupleBatch(output, result.batch());
            }
            case Failure failure -> {
                output.writeLong(failure.requestId());
                writeString(output, failure.code());
                writeString(output, failure.message());
                writeSourceSpan(output, failure.span());
                output.writeBoolean(failure.fatal());
            }
            case CloseRequest request -> writeString(output, request.reason());
            case Goodbye goodbye -> writeString(output, goodbye.message());
        }
    }

    private static Message readPayload(DataInputStream input, MessageType type) throws IOException {
        return switch (type) {
            case CLIENT_HELLO -> {
                String clientName = readString(input);
                short protocolVersion = input.readShort();
                String user = input.available() > 0 ? readString(input) : "";
                String password = input.available() > 0 ? readString(input) : "";
                yield new ClientHello(clientName, protocolVersion, user, password);
            }
            case SERVER_HELLO -> new ServerHello(readString(input), input.readShort(), input.readInt(), readString(input));
            case EXECUTE_REQUEST -> new ExecuteRequest(input.readLong(), readString(input), input.readLong());
            case EXECUTE_RESULT -> new ExecuteResult(input.readLong(), readBatchResult(input));
            case PREPARE_REQUEST -> new PrepareRequest(input.readLong(), readString(input));
            case PREPARE_RESULT -> new PrepareResult(input.readLong(), readPreparedDescription(input));
            case EXECUTE_PREPARED_REQUEST -> new ExecutePreparedRequest(input.readLong(), input.readLong(),
                    readStringList(input), input.readLong());
            case CLOSE_PREPARED_REQUEST -> new ClosePreparedRequest(input.readLong(), input.readLong());
            case CLOSE_PREPARED_RESULT -> new ClosePreparedResult(input.readLong());
            case CANCEL_REQUEST -> new CancelRequest(input.readLong(), readString(input), input.readLong());
            case CANCEL_RESULT -> new CancelResult(input.readLong(), input.readBoolean());
            case TRANSACTION_REQUEST -> new TransactionRequest(input.readLong(), readString(input), readString(input));
            case TRANSACTION_RESULT -> new TransactionResult(input.readLong(), input.readBoolean());
            case METADATA_REQUEST -> new MetadataRequest(input.readLong(), readString(input), readStringList(input));
            case METADATA_RESULT -> new MetadataResult(input.readLong(), readTupleBatch(input));
            case FAILURE -> new Failure(input.readLong(), readString(input), readString(input), readSourceSpan(input), input.readBoolean());
            case CLOSE_REQUEST -> new CloseRequest(readString(input));
            case GOODBYE -> new Goodbye(readString(input));
        };
    }

    private static void writeBatchResult(DataOutputStream output, EngineApi.BatchResult result) throws IOException {
        output.writeInt(result.statements().size());
        for (EngineApi.StatementResult statement : result.statements()) {
            writeString(output, statement.commandTag());
            output.writeLong(statement.updateCount());
            writeString(output, statement.explainPlan());
            writeTupleBatch(output, statement.batch());
            writeTupleBatch(output, statement.generatedKeys());
        }
    }

    private static EngineApi.BatchResult readBatchResult(DataInputStream input) throws IOException {
        int statementCount = readCount(input);
        List<EngineApi.StatementResult> statements = new ArrayList<>(statementCount);
        for (int index = 0; index < statementCount; index++) {
            String commandTag = readString(input);
            long updateCount = input.readLong();
            String explainPlan = readString(input);
            Common.TupleBatch batch = readTupleBatch(input);
            Common.TupleBatch generatedKeys = readTupleBatch(input);
            statements.add(new EngineApi.StatementResult(commandTag, updateCount, batch, generatedKeys, explainPlan));
        }
        return new EngineApi.BatchResult(statements);
    }

    private static void writePreparedDescription(DataOutputStream output, EngineApi.PreparedStatementDescription description)
            throws IOException {
        output.writeLong(description.statementId());
        writeString(output, description.sql());
        output.writeInt(description.parameterCount());
        writeParameterDescriptions(output, description.parameterDescriptions());
        writeResultColumns(output, description.resultColumns());
    }

    private static EngineApi.PreparedStatementDescription readPreparedDescription(DataInputStream input) throws IOException {
        long statementId = input.readLong();
        String sql = readString(input);
        int parameterCount = readCount(input);
        List<EngineApi.ParameterDescription> parameterDescriptions = readParameterDescriptions(input);
        List<Common.ResultColumn> resultColumns = readResultColumns(input);
        return new EngineApi.PreparedStatementDescription(statementId, sql, parameterCount, parameterDescriptions, resultColumns);
    }

    private static void writeParameterDescriptions(DataOutputStream output, List<EngineApi.ParameterDescription> parameters)
            throws IOException {
        output.writeInt(parameters.size());
        for (EngineApi.ParameterDescription parameter : parameters) {
            output.writeInt(parameter.index());
            output.writeByte(parameter.type().ordinal());
            output.writeInt(parameter.precision() == null ? -1 : parameter.precision());
            output.writeInt(parameter.scale() == null ? -1 : parameter.scale());
            output.writeBoolean(parameter.nullable());
        }
    }

    private static List<EngineApi.ParameterDescription> readParameterDescriptions(DataInputStream input) throws IOException {
        int count = readCount(input);
        List<EngineApi.ParameterDescription> parameters = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int ordinal = readCount(input);
            Common.DataType type = readDataType(input);
            int precision = input.readInt();
            int scale = input.readInt();
            boolean nullable = input.readBoolean();
            parameters.add(new EngineApi.ParameterDescription(ordinal, type,
                    precision < 0 ? null : precision,
                    scale < 0 ? null : scale,
                    nullable));
        }
        return parameters;
    }

    private static void writeResultColumns(DataOutputStream output, List<Common.ResultColumn> columns) throws IOException {
        output.writeInt(columns.size());
        for (Common.ResultColumn column : columns) {
            writeString(output, column.name());
            output.writeByte(column.type().ordinal());
            output.writeInt(column.precision() == null ? -1 : column.precision());
            output.writeInt(column.scale() == null ? -1 : column.scale());
        }
    }

    private static List<Common.ResultColumn> readResultColumns(DataInputStream input) throws IOException {
        int columnCount = readCount(input);
        List<Common.ResultColumn> columns = new ArrayList<>(columnCount);
        for (int index = 0; index < columnCount; index++) {
            String name = readString(input);
            Common.DataType type = readDataType(input);
            int precision = input.readInt();
            int scale = input.readInt();
            columns.add(new Common.ResultColumn(name, type,
                    precision < 0 ? null : precision,
                    scale < 0 ? null : scale));
        }
        return columns;
    }

    private static void writeTupleBatch(DataOutputStream output, Common.TupleBatch batch) throws IOException {
        writeResultColumns(output, batch.columns());
        output.writeInt(batch.rows().size());
        for (Common.ResultRow row : batch.rows()) {
            output.writeInt(row.values().size());
            for (Common.Value value : row.values()) {
                writeValue(output, value);
            }
        }
    }

    private static Common.TupleBatch readTupleBatch(DataInputStream input) throws IOException {
        List<Common.ResultColumn> columns = readResultColumns(input);
        int rowCount = readCount(input);
        List<Common.ResultRow> rows = new ArrayList<>(rowCount);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            int valueCount = readCount(input);
            List<Common.Value> values = new ArrayList<>(valueCount);
            for (int valueIndex = 0; valueIndex < valueCount; valueIndex++) {
                values.add(readValue(input));
            }
            rows.add(new Common.ResultRow(values));
        }
        return new Common.TupleBatch(columns, rows);
    }

    private static void writeValue(DataOutputStream output, Common.Value value) throws IOException {
        output.writeByte(value.type().ordinal());
        output.writeBoolean(value.isNull());
        if (value.isNull()) {
            return;
        }
        switch (value.type()) {
            case INTEGER -> output.writeInt(value.asInt());
            case BIGINT -> output.writeLong(value.asLong());
            case BOOLEAN -> output.writeBoolean(value.asBoolean());
            case TEXT -> writeString(output, value.asText());
            case DECIMAL -> writeString(output, value.asDecimal().toPlainString());
            case DATE -> output.writeLong(value.asDate().toEpochDay());
            case TIME -> output.writeLong(value.asTime().toNanoOfDay());
            case TIMESTAMP -> writeString(output, value.asTimestamp().toString());
        }
    }

    private static Common.Value readValue(DataInputStream input) throws IOException {
        Common.DataType type = readDataType(input);
        boolean isNull = input.readBoolean();
        if (isNull) {
            return Common.Value.nullValue(type);
        }
        return switch (type) {
            case INTEGER -> Common.Value.integer(input.readInt());
            case BIGINT -> Common.Value.bigint(input.readLong());
            case BOOLEAN -> Common.Value.bool(input.readBoolean());
            case TEXT -> Common.Value.text(readString(input));
            case DECIMAL -> Common.Value.decimal(new java.math.BigDecimal(readString(input)));
            case DATE -> Common.Value.date(java.time.LocalDate.ofEpochDay(input.readLong()));
            case TIME -> Common.Value.time(java.time.LocalTime.ofNanoOfDay(input.readLong()));
            case TIMESTAMP -> Common.Value.timestamp(Common.Value.parseTimestamp(readString(input)));
        };
    }

    private static Common.DataType readDataType(DataInputStream input) throws IOException {
        int ordinal = input.readUnsignedByte();
        Common.DataType[] values = Common.DataType.values();
        if (ordinal >= values.length) {
            throw new ProtocolException("Invalid data type ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    private static void writeSourceSpan(DataOutputStream output, Common.SourceSpan span) throws IOException {
        output.writeInt(span.line());
        output.writeInt(span.column());
        output.writeInt(span.endLine());
        output.writeInt(span.endColumn());
    }

    private static Common.SourceSpan readSourceSpan(DataInputStream input) throws IOException {
        return new Common.SourceSpan(input.readInt(), input.readInt(), input.readInt(), input.readInt());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readCount(input);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of stream while reading string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream input) throws IOException {
        int value = input.readInt();
        if (value < 0) {
            throw new ProtocolException("Negative count: " + value);
        }
        return value;
    }

    private static void writeStringList(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static List<String> readStringList(DataInputStream input) throws IOException {
        int count = readCount(input);
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readString(input));
        }
        return values;
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static String normalizeToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("sessionToken must not be blank");
        }
        return normalized;
    }

    private static int normalizeFrameLimit(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        return maxFrameBytes;
    }

    private enum MessageType {
        CLIENT_HELLO(1),
        SERVER_HELLO(2),
        EXECUTE_REQUEST(3),
        EXECUTE_RESULT(4),
        PREPARE_REQUEST(5),
        PREPARE_RESULT(6),
        EXECUTE_PREPARED_REQUEST(7),
        CLOSE_PREPARED_REQUEST(8),
        CLOSE_PREPARED_RESULT(9),
        CANCEL_REQUEST(10),
        CANCEL_RESULT(11),
        TRANSACTION_REQUEST(12),
        TRANSACTION_RESULT(13),
        METADATA_REQUEST(14),
        METADATA_RESULT(15),
        FAILURE(16),
        CLOSE_REQUEST(17),
        GOODBYE(18);

        private final int code;

        MessageType(int code) {
            this.code = code;
        }

        private static MessageType forMessage(Message message) {
            return switch (message) {
                case ClientHello ignored -> CLIENT_HELLO;
                case ServerHello ignored -> SERVER_HELLO;
                case ExecuteRequest ignored -> EXECUTE_REQUEST;
                case ExecuteResult ignored -> EXECUTE_RESULT;
                case PrepareRequest ignored -> PREPARE_REQUEST;
                case PrepareResult ignored -> PREPARE_RESULT;
                case ExecutePreparedRequest ignored -> EXECUTE_PREPARED_REQUEST;
                case ClosePreparedRequest ignored -> CLOSE_PREPARED_REQUEST;
                case ClosePreparedResult ignored -> CLOSE_PREPARED_RESULT;
                case CancelRequest ignored -> CANCEL_REQUEST;
                case CancelResult ignored -> CANCEL_RESULT;
                case TransactionRequest ignored -> TRANSACTION_REQUEST;
                case TransactionResult ignored -> TRANSACTION_RESULT;
                case MetadataRequest ignored -> METADATA_REQUEST;
                case MetadataResult ignored -> METADATA_RESULT;
                case Failure ignored -> FAILURE;
                case CloseRequest ignored -> CLOSE_REQUEST;
                case Goodbye ignored -> GOODBYE;
            };
        }

        private static MessageType fromCode(int code) throws ProtocolException {
            for (MessageType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new ProtocolException("Unknown message type: " + code);
        }
    }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
