package dev.daisybase.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PreparedSqlTemplate {
    private final List<String> segments;
    private final int parameterCount;

    private PreparedSqlTemplate(List<String> segments, int parameterCount) {
        this.segments = List.copyOf(segments);
        this.parameterCount = parameterCount;
    }

    public static PreparedSqlTemplate parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Prepared SQL must not be empty");
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
                    throw new IllegalArgumentException("Prepared statements require exactly one SQL statement");
                }
                segments.add(current.toString());
                current.setLength(0);
                parameters++;
                continue;
            }
            if (!inString && ch == ';') {
                if (sawTerminator || !trailingOnly(sql, index + 1)) {
                    throw new IllegalArgumentException("Prepared statements require exactly one SQL statement");
                }
                sawTerminator = true;
                continue;
            }
            if (sawTerminator && !Character.isWhitespace(ch)) {
                throw new IllegalArgumentException("Prepared statements require exactly one SQL statement");
            }
            current.append(ch);
        }
        if (inString) {
            throw new IllegalArgumentException("Unterminated string literal in prepared SQL");
        }
        segments.add(current.toString());
        return new PreparedSqlTemplate(segments, parameters);
    }

    public int parameterCount() {
        return parameterCount;
    }

    public String render(List<String> parameterLiterals) {
        Objects.requireNonNull(parameterLiterals, "parameterLiterals");
        if (parameterLiterals.size() != parameterCount) {
            throw new IllegalArgumentException("Expected " + parameterCount
                    + " parameters but received " + parameterLiterals.size());
        }
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < parameterCount; index++) {
            String literal = parameterLiterals.get(index);
            if (literal == null) {
                throw new IllegalArgumentException("Parameter " + (index + 1) + " is not bound");
            }
            sql.append(segments.get(index)).append(literal);
        }
        sql.append(segments.getLast());
        return sql.toString();
    }

    public String renderNulls() {
        return render(List.copyOf(java.util.Collections.nCopies(parameterCount, "NULL")));
    }

    private static boolean trailingOnly(String sql, int start) {
        for (int index = start; index < sql.length(); index++) {
            if (!Character.isWhitespace(sql.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
