package dev.javadb.common;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Common {
    private Common() {
    }

    public enum DataType {
        INTEGER,
        BIGINT,
        BOOLEAN,
        TEXT,
        DECIMAL,
        DATE,
        TIME,
        TIMESTAMP;

        public static DataType fromSql(String text) {
            return switch (text.toUpperCase(Locale.ROOT)) {
                case "INT", "INTEGER" -> INTEGER;
                case "BIGINT", "NUMBER" -> BIGINT;
                case "BOOL", "BOOLEAN" -> BOOLEAN;
                case "TEXT", "STRING", "VARCHAR", "VARCHAR2", "CHAR", "CLOB" -> TEXT;
                case "NUMERIC", "DECIMAL" -> DECIMAL;
                case "DATE" -> DATE;
                case "TIME" -> TIME;
                case "TIMESTAMP", "DATETIME" -> TIMESTAMP;
                default -> throw new DatabaseException(ErrorCode.UNSUPPORTED_FEATURE, "Unsupported data type: " + text);
            };
        }

        public boolean numeric() {
            return this == INTEGER || this == BIGINT || this == DECIMAL;
        }

        public int defaultPrecision() {
            return switch (this) {
                case INTEGER -> 10;
                case BIGINT -> 19;
                case BOOLEAN -> 1;
                case TEXT -> 32_767;
                case DECIMAL -> 38;
                case DATE -> 10;
                case TIME -> 12;
                case TIMESTAMP -> 29;
            };
        }

        public int defaultScale() {
            return this == DECIMAL ? 18 : 0;
        }
    }

    public enum IsolationLevel {
        READ_COMMITTED,
        REPEATABLE_READ,
        SERIALIZABLE;

        public static IsolationLevel fromSqlWords(List<String> words) {
            if (words.isEmpty()) {
                return READ_COMMITTED;
            }
            String joined = String.join(" ", words).toUpperCase(Locale.ROOT);
            return switch (joined) {
                case "READ COMMITTED" -> READ_COMMITTED;
                case "REPEATABLE READ" -> REPEATABLE_READ;
                case "SERIALIZABLE" -> SERIALIZABLE;
                case "READ UNCOMMITTED" -> READ_COMMITTED;
                default -> throw new DatabaseException(ErrorCode.SEMANTIC_ERROR, "Unsupported isolation level: " + joined);
            };
        }
    }

    public enum ErrorCode {
        PARSE_ERROR,
        SEMANTIC_ERROR,
        STORAGE_ERROR,
        TRANSACTION_CONFLICT,
        CONSTRAINT_VIOLATION,
        QUERY_CANCELLED,
        QUERY_TIMEOUT,
        UNSUPPORTED_FEATURE,
        INTERNAL_ERROR
    }

    public static final class DatabaseException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final ErrorCode code;
        private final SourceSpan span;

        public DatabaseException(ErrorCode code, String message) {
            this(code, message, SourceSpan.NONE);
        }

        public DatabaseException(ErrorCode code, String message, Throwable cause) {
            this(code, message, SourceSpan.NONE, cause);
        }

        public DatabaseException(ErrorCode code, String message, SourceSpan span) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            this.span = span == null ? SourceSpan.NONE : span;
        }

        public DatabaseException(ErrorCode code, String message, SourceSpan span, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
            this.span = span == null ? SourceSpan.NONE : span;
        }

        public ErrorCode code() {
            return code;
        }

        public SourceSpan span() {
            return span;
        }
    }

    public static final class ExecutionControl {
        private final long timeoutMillis;
        private final long deadlineNanos;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String cancellationReason = "Query was cancelled";

        private ExecutionControl(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            this.deadlineNanos = timeoutMillis <= 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMillis * 1_000_000L;
        }

        public static ExecutionControl none() {
            return new ExecutionControl(0);
        }

        public static ExecutionControl timeoutMillis(long timeoutMillis) {
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("timeoutMillis must be non-negative");
            }
            return new ExecutionControl(timeoutMillis);
        }

        public long timeoutMillis() {
            return timeoutMillis;
        }

        public void cancel() {
            cancel("Query was cancelled");
        }

        public void cancel(String reason) {
            cancellationReason = reason == null || reason.isBlank() ? "Query was cancelled" : reason;
            cancelled.set(true);
        }

        public boolean cancelled() {
            return cancelled.get();
        }

        public void check() {
            if (cancelled()) {
                throw new DatabaseException(ErrorCode.QUERY_CANCELLED, cancellationReason);
            }
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
                cancelled.set(true);
                throw new DatabaseException(ErrorCode.QUERY_TIMEOUT,
                        "Query timed out after " + timeoutMillis + " ms");
            }
        }
    }

    public record SourceSpan(int line, int column, int endLine, int endColumn) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public static final SourceSpan NONE = new SourceSpan(1, 1, 1, 1);
    }

    public record ObjectId(long value) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record RowId(long value) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public boolean temporary() {
            return value < 0;
        }
    }

    public record TransactionId(long value) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record Lsn(long value) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record Value(DataType type, Object raw) implements Comparable<Value>, Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public Value {
            Objects.requireNonNull(type, "type");
            if (raw != null) {
                switch (type) {
                    case INTEGER -> raw = ((Number) raw).intValue();
                    case BIGINT -> raw = ((Number) raw).longValue();
                    case BOOLEAN -> raw = (Boolean) raw;
                    case TEXT -> raw = raw.toString();
                    case DECIMAL -> raw = raw instanceof BigDecimal bigDecimal
                            ? bigDecimal
                            : new BigDecimal(raw.toString());
                    case DATE -> raw = raw instanceof LocalDate localDate
                            ? localDate
                            : LocalDate.parse(raw.toString());
                    case TIME -> raw = raw instanceof LocalTime localTime
                            ? localTime
                            : LocalTime.parse(raw.toString());
                    case TIMESTAMP -> raw = raw instanceof LocalDateTime localDateTime
                            ? localDateTime
                            : parseTimestamp(raw.toString());
                }
            }
        }

        public static Value integer(Integer value) {
            return new Value(DataType.INTEGER, value);
        }

        public static Value bigint(Long value) {
            return new Value(DataType.BIGINT, value);
        }

        public static Value bool(Boolean value) {
            return new Value(DataType.BOOLEAN, value);
        }

        public static Value text(String value) {
            return new Value(DataType.TEXT, value);
        }

        public static Value decimal(BigDecimal value) {
            return new Value(DataType.DECIMAL, value);
        }

        public static Value date(LocalDate value) {
            return new Value(DataType.DATE, value);
        }

        public static Value time(LocalTime value) {
            return new Value(DataType.TIME, value);
        }

        public static Value timestamp(LocalDateTime value) {
            return new Value(DataType.TIMESTAMP, value);
        }

        public static Value nullValue(DataType type) {
            return new Value(type, null);
        }

        public static Value fromLiteral(Object literal) {
            if (literal == null) {
                return Value.text(null);
            }
            if (literal instanceof Integer integer) {
                return integer(integer);
            }
            if (literal instanceof Long longValue) {
                return bigint(longValue);
            }
            if (literal instanceof BigDecimal decimalValue) {
                return decimal(decimalValue);
            }
            if (literal instanceof Boolean boolValue) {
                return bool(boolValue);
            }
            if (literal instanceof LocalDate dateValue) {
                return date(dateValue);
            }
            if (literal instanceof LocalTime timeValue) {
                return time(timeValue);
            }
            if (literal instanceof LocalDateTime timestampValue) {
                return timestamp(timestampValue);
            }
            return text(literal.toString());
        }

        public boolean isNull() {
            return raw == null;
        }

        public int asInt() {
            return ((Number) raw).intValue();
        }

        public long asLong() {
            return ((Number) raw).longValue();
        }

        public boolean asBoolean() {
            return (Boolean) raw;
        }

        public String asText() {
            return raw == null ? null : switch (type) {
                case INTEGER, BIGINT, BOOLEAN, TEXT -> raw.toString();
                case DECIMAL -> asDecimal().stripTrailingZeros().toPlainString();
                case DATE -> asDate().toString();
                case TIME -> asTime().toString();
                case TIMESTAMP -> asTimestamp().toString();
            };
        }

        public BigDecimal asDecimal() {
            return switch (type) {
                case INTEGER -> BigDecimal.valueOf(asInt());
                case BIGINT -> BigDecimal.valueOf(asLong());
                case DECIMAL -> (BigDecimal) raw;
                case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> new BigDecimal(asText());
            };
        }

        public LocalDate asDate() {
            return switch (type) {
                case DATE -> (LocalDate) raw;
                case TEXT -> LocalDate.parse(asText());
                case TIMESTAMP -> asTimestamp().toLocalDate();
                case INTEGER, BIGINT, BOOLEAN, DECIMAL, TIME -> throw new IllegalStateException("Cannot coerce " + type + " to DATE");
            };
        }

        public LocalTime asTime() {
            return switch (type) {
                case TIME -> (LocalTime) raw;
                case TEXT -> LocalTime.parse(asText());
                case TIMESTAMP -> asTimestamp().toLocalTime();
                case INTEGER, BIGINT, BOOLEAN, DECIMAL, DATE -> throw new IllegalStateException("Cannot coerce " + type + " to TIME");
            };
        }

        public LocalDateTime asTimestamp() {
            return switch (type) {
                case TIMESTAMP -> (LocalDateTime) raw;
                case DATE -> asDate().atStartOfDay();
                case TEXT -> parseTimestamp(asText());
                case INTEGER, BIGINT, BOOLEAN, DECIMAL, TIME -> throw new IllegalStateException("Cannot coerce " + type + " to TIMESTAMP");
            };
        }

        public Value castTo(DataType targetType) {
            if (isNull()) {
                return new Value(targetType, null);
            }
            if (targetType == type) {
                return this;
            }
            return switch (targetType) {
                case INTEGER -> new Value(targetType, switch (type) {
                    case INTEGER -> asInt();
                    case BIGINT -> Math.toIntExact(asLong());
                    case DECIMAL -> asDecimal().intValueExact();
                    case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> Integer.parseInt(asText());
                });
                case BIGINT -> new Value(targetType, switch (type) {
                    case INTEGER -> (long) asInt();
                    case BIGINT -> asLong();
                    case DECIMAL -> asDecimal().longValueExact();
                    case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> Long.parseLong(asText());
                });
                case BOOLEAN -> new Value(targetType, Boolean.parseBoolean(asText()));
                case TEXT -> new Value(targetType, asText());
                case DECIMAL -> new Value(targetType, asDecimal());
                case DATE -> new Value(targetType, asDate());
                case TIME -> new Value(targetType, asTime());
                case TIMESTAMP -> new Value(targetType, asTimestamp());
            };
        }

        @Override
        public int compareTo(Value other) {
            if (other == null) {
                return 1;
            }
            if (isNull() || other.isNull()) {
                throw new IllegalStateException("Null values are not directly comparable");
            }
            if (type != other.type) {
                if (type.numeric() && other.type.numeric()) {
                    return asDecimal().compareTo(other.asDecimal());
                }
                return castTo(other.type()).compareTo(other);
            }
            return switch (type) {
                case INTEGER -> Integer.compare(asInt(), other.asInt());
                case BIGINT -> Long.compare(asLong(), other.asLong());
                case BOOLEAN -> Boolean.compare(asBoolean(), other.asBoolean());
                case TEXT -> asText().compareTo(other.asText());
                case DECIMAL -> asDecimal().compareTo(other.asDecimal());
                case DATE -> asDate().compareTo(other.asDate());
                case TIME -> asTime().compareTo(other.asTime());
                case TIMESTAMP -> asTimestamp().compareTo(other.asTimestamp());
            };
        }

        public static LocalDateTime parseTimestamp(String text) {
            return text.contains("T")
                    ? LocalDateTime.parse(text)
                    : LocalDateTime.parse(text.replace(' ', 'T'));
        }
    }

    public record ResultColumn(String name, DataType type, Integer precision, Integer scale) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public ResultColumn(String name, DataType type) {
            this(name, type, null, null);
        }
    }

    public record ResultRow(List<Value> values) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public ResultRow {
            values = List.copyOf(values);
        }

        public Value get(int index) {
            return values.get(index);
        }
    }

    public record TupleBatch(List<ResultColumn> columns, List<ResultRow> rows) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public static TupleBatch empty() {
            return new TupleBatch(List.of(), List.of());
        }

        public TupleBatch {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    public static final class Values {
        private static final Comparator<Value> NULLS_LAST = (left, right) -> {
            if (left == null || left.isNull()) {
                return right == null || right.isNull() ? 0 : 1;
            }
            if (right == null || right.isNull()) {
                return -1;
            }
            return left.compareTo(right);
        };

        private Values() {
        }

        public static Value coerce(Value value, DataType targetType) {
            return value == null ? Value.nullValue(targetType) : value.castTo(targetType);
        }

        public static Value coerce(Value value, DataType targetType, Integer precision, Integer scale) {
            Value coerced = coerce(value, targetType);
            if (coerced.isNull() || targetType != DataType.DECIMAL) {
                return coerced;
            }
            if (precision == null && scale == null) {
                return coerced;
            }
            int effectivePrecision = precision == null || precision <= 0 ? targetType.defaultPrecision() : precision;
            int effectiveScale = scale == null || scale < 0 ? 0 : scale;
            if (effectiveScale > effectivePrecision) {
                throw new DatabaseException(ErrorCode.SEMANTIC_ERROR,
                        "DECIMAL scale " + effectiveScale + " exceeds precision " + effectivePrecision);
            }
            BigDecimal scaled = coerced.asDecimal().setScale(effectiveScale, RoundingMode.HALF_UP);
            if (scaled.precision() > effectivePrecision) {
                throw new DatabaseException(ErrorCode.CONSTRAINT_VIOLATION,
                        "DECIMAL value " + scaled.toPlainString()
                                + " exceeds precision " + effectivePrecision + " and scale " + effectiveScale);
            }
            return Value.decimal(scaled);
        }

        public static Boolean equals3vl(Value left, Value right) {
            if (left == null || right == null || left.isNull() || right.isNull()) {
                return null;
            }
            if (left.type().numeric() && right.type().numeric()) {
                return left.asDecimal().compareTo(right.asDecimal()) == 0;
            }
            return left.compareTo(coerce(right, left.type())) == 0;
        }

        public static Boolean compare(Value left, Value right, String operator) {
            if (left == null || right == null || left.isNull() || right.isNull()) {
                return null;
            }
            int compared = left.type().numeric() && right.type().numeric()
                    ? left.asDecimal().compareTo(right.asDecimal())
                    : left.compareTo(coerce(right, left.type()));
            return switch (operator) {
                case "=" -> compared == 0;
                case "!=", "<>" -> compared != 0;
                case ">" -> compared > 0;
                case ">=" -> compared >= 0;
                case "<" -> compared < 0;
                case "<=" -> compared <= 0;
                default -> throw new DatabaseException(ErrorCode.INTERNAL_ERROR, "Unknown comparison operator: " + operator);
            };
        }

        public static Value fromJava(Object value, DataType targetType) {
            if (value == null) {
                return Value.nullValue(targetType);
            }
            return switch (targetType) {
                case INTEGER -> Value.integer(((Number) value).intValue());
                case BIGINT -> Value.bigint(((Number) value).longValue());
                case BOOLEAN -> Value.bool((Boolean) value);
                case TEXT -> Value.text(value.toString());
                case DECIMAL -> Value.decimal(value instanceof BigDecimal bigDecimal ? bigDecimal : new BigDecimal(value.toString()));
                case DATE -> Value.date(value instanceof LocalDate localDate ? localDate : LocalDate.parse(value.toString()));
                case TIME -> Value.time(value instanceof LocalTime localTime ? localTime : LocalTime.parse(value.toString()));
                case TIMESTAMP -> Value.timestamp(value instanceof LocalDateTime localDateTime
                        ? localDateTime
                        : Value.parseTimestamp(value.toString()));
            };
        }

        public static String encodeValue(Value value) {
            String payload = value == null || value.raw() == null ? "" : Base64.getUrlEncoder().encodeToString(value.asText().getBytes(StandardCharsets.UTF_8));
            String type = value == null ? DataType.TEXT.name() : value.type().name();
            boolean isNull = value == null || value.raw() == null;
            return type + ":" + isNull + ":" + payload;
        }

        public static Value decodeValue(String text) {
            String[] parts = text.split(":", 3);
            DataType type = DataType.valueOf(parts[0]);
            boolean isNull = Boolean.parseBoolean(parts[1]);
            if (isNull) {
                return Value.nullValue(type);
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            return switch (type) {
                case INTEGER -> Value.integer(Integer.parseInt(payload));
                case BIGINT -> Value.bigint(Long.parseLong(payload));
                case BOOLEAN -> Value.bool(Boolean.parseBoolean(payload));
                case TEXT -> Value.text(payload);
                case DECIMAL -> Value.decimal(new BigDecimal(payload));
                case DATE -> Value.date(LocalDate.parse(payload));
                case TIME -> Value.time(LocalTime.parse(payload));
                case TIMESTAMP -> Value.timestamp(Value.parseTimestamp(payload));
            };
        }

        public static String encodeString(String text) {
            return Base64.getUrlEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        public static String decodeString(String text) {
            return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
        }

        public static Comparator<Value> nullsLastComparator() {
            return NULLS_LAST;
        }

        public static boolean truthy(Boolean value) {
            return Boolean.TRUE.equals(value);
        }

        public static List<Value> copyValues(List<Value> values) {
            return new ArrayList<>(values);
        }
    }
}
