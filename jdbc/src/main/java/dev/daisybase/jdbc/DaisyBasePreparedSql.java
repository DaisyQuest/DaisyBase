package dev.daisybase.jdbc;

import java.math.BigDecimal;
import java.net.URL;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

final class DaisyBasePreparedSql {
    private final List<String> segments;
    private final int parameterCount;

    private DaisyBasePreparedSql(List<String> segments, int parameterCount) {
        this.segments = segments;
        this.parameterCount = parameterCount;
    }

    static DaisyBasePreparedSql parse(String sql) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("Prepared SQL must not be empty");
        }
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean sawTerminator = false;
        int parameters = 0;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '\'') {
                current.append(ch);
                if (inString && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    current.append('\'');
                    index++;
                    continue;
                }
                inString = !inString;
                continue;
            }
            if (!inString && ch == '?') {
                if (sawTerminator) {
                    throw new SQLException("Prepared statements require exactly one SQL statement");
                }
                segments.add(current.toString());
                current.setLength(0);
                parameters++;
                continue;
            }
            if (!inString && ch == ';') {
                if (sawTerminator || !trailingOnly(sql, index + 1)) {
                    throw new SQLException("Prepared statements require exactly one SQL statement");
                }
                sawTerminator = true;
                continue;
            }
            if (sawTerminator && !Character.isWhitespace(ch)) {
                throw new SQLException("Prepared statements require exactly one SQL statement");
            }
            current.append(ch);
        }
        if (inString) {
            throw new SQLException("Unterminated string literal in prepared SQL");
        }
        segments.add(current.toString());
        return new DaisyBasePreparedSql(List.copyOf(segments), parameters);
    }

    private static boolean trailingOnly(String sql, int start) {
        for (int index = start; index < sql.length(); index++) {
            if (!Character.isWhitespace(sql.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    int parameterCount() {
        return parameterCount;
    }

    String render(List<BoundParameter> parameters) throws SQLException {
        List<String> literals = renderLiterals(parameters);
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < parameterCount; index++) {
            sql.append(segments.get(index)).append(literals.get(index));
        }
        sql.append(segments.getLast());
        return sql.toString();
    }

    List<String> renderLiterals(List<BoundParameter> parameters) throws SQLException {
        if (parameters.size() != parameterCount) {
            throw new SQLException("Expected " + parameterCount + " parameters but received " + parameters.size());
        }
        List<String> literals = new ArrayList<>(parameterCount);
        for (int index = 0; index < parameterCount; index++) {
            BoundParameter parameter = parameters.get(index);
            if (parameter == null) {
                throw new SQLException("Parameter " + (index + 1) + " is not bound");
            }
            literals.add(parameter.sqlLiteral());
        }
        return literals;
    }

    record BoundParameter(String sqlLiteral) {
        static BoundParameter of(Object value, Integer sqlType) throws SQLException {
            if (value == null) {
                return new BoundParameter("NULL");
            }
            String nativeLiteral = DaisyBaseJdbcObjects.nativeParameterLiteral(value, sqlType);
            if (nativeLiteral != null) {
                return new BoundParameter(nativeLiteral);
            }
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                return new BoundParameter(String.valueOf(((Number) value).longValue()));
            }
            if (value instanceof Float || value instanceof Double) {
                return new BoundParameter(new BigDecimal(value.toString()).toPlainString());
            }
            if (value instanceof BigDecimal bigDecimal) {
                return new BoundParameter(bigDecimal.toPlainString());
            }
            if (value instanceof Boolean bool) {
                return new BoundParameter(bool ? "TRUE" : "FALSE");
            }
            if (value instanceof java.sql.Date date) {
                return typedLiteral("DATE", date.toLocalDate().toString());
            }
            if (value instanceof Time time) {
                return typedLiteral("TIME", time.toLocalTime().toString());
            }
            if (value instanceof Timestamp timestamp) {
                return typedLiteral("TIMESTAMP", timestamp.toLocalDateTime().toString());
            }
            if (value instanceof LocalDate localDate) {
                return typedLiteral("DATE", localDate.toString());
            }
            if (value instanceof LocalTime localTime) {
                return typedLiteral("TIME", localTime.toString());
            }
            if (value instanceof LocalDateTime localDateTime) {
                return typedLiteral("TIMESTAMP", localDateTime.toString());
            }
            if (value instanceof OffsetDateTime offsetDateTime) {
                return typedLiteral("TIMESTAMP", offsetDateTime.toLocalDateTime().toString());
            }
            if (value instanceof Instant instant) {
                return typedLiteral("TIMESTAMP", LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC).toString());
            }
            if (value instanceof URL url) {
                return quoted(url.toExternalForm());
            }
            if (value instanceof String text || value instanceof Character) {
                return quoted(value.toString());
            }
            if (sqlType != null && sqlType == Types.VARCHAR) {
                return quoted(value.toString());
            }
            throw new SQLFeatureNotSupportedException("Unsupported prepared parameter type: " + value.getClass().getName());
        }

        private static BoundParameter quoted(String text) {
            return new BoundParameter('\'' + text.replace("'", "''") + '\'');
        }

        private static BoundParameter typedLiteral(String typeName, String text) {
            return new BoundParameter(typeName + " '" + text.replace("'", "''") + "'");
        }
    }
}
