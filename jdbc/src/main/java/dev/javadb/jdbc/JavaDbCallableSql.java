package dev.javadb.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JavaDbCallableSql {
    private final String nativeSql;
    private final String schemaName;
    private final String routineName;
    private final boolean hasReturnValue;
    private final int argumentCount;

    private JavaDbCallableSql(String nativeSql, String schemaName, String routineName,
                              boolean hasReturnValue, int argumentCount) {
        this.nativeSql = nativeSql;
        this.schemaName = schemaName;
        this.routineName = routineName;
        this.hasReturnValue = hasReturnValue;
        this.argumentCount = argumentCount;
    }

    static JavaDbCallableSql parse(String sql) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("Callable SQL must not be empty");
        }
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        if (trimmed.startsWith("{")) {
            if (!trimmed.endsWith("}")) {
                throw new SQLException("Malformed JDBC call escape syntax");
            }
            trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
        }

        boolean hasReturnValue = false;
        if (trimmed.startsWith("?")) {
            hasReturnValue = true;
            trimmed = trimmed.substring(1).stripLeading();
            if (!trimmed.startsWith("=")) {
                throw new SQLException("Expected '=' after callable return marker");
            }
            trimmed = trimmed.substring(1).stripLeading();
        }
        if (!trimmed.regionMatches(true, 0, "CALL", 0, "CALL".length())) {
            throw new SQLException("Callable SQL must use CALL syntax");
        }
        String invocation = trimmed.substring("CALL".length()).strip();
        if (invocation.isBlank()) {
            throw new SQLException("Callable SQL is missing a routine name");
        }

        String routineReference;
        int argumentCount = 0;
        if (invocation.contains("(")) {
            int open = invocation.indexOf('(');
            int close = invocation.lastIndexOf(')');
            if (close < open || !invocation.substring(close + 1).isBlank()) {
                throw new SQLException("Malformed CALL argument list");
            }
            routineReference = invocation.substring(0, open).strip();
            String argumentList = invocation.substring(open + 1, close).strip();
            if (!argumentList.isBlank()) {
                List<String> arguments = splitArguments(argumentList);
                for (String argument : arguments) {
                    if (!"?".equals(argument.strip())) {
                        throw new SQLException("Callable statements require '?' placeholders for every routine argument");
                    }
                }
                argumentCount = arguments.size();
            }
        } else {
            routineReference = invocation;
        }
        if (routineReference.isBlank()) {
            throw new SQLException("Callable SQL is missing a routine name");
        }

        String[] parts = routineReference.split("\\.", 2);
        String schemaName = parts.length == 2 ? parts[0].toLowerCase(Locale.ROOT) : "public";
        String routineName = parts.length == 2 ? parts[1].toLowerCase(Locale.ROOT) : parts[0].toLowerCase(Locale.ROOT);
        String nativeSql = argumentCount == 0
                ? "CALL " + routineReference
                : "CALL " + routineReference + "(" + "?,".repeat(argumentCount).replaceAll(",$", "") + ")";
        return new JavaDbCallableSql(nativeSql, schemaName, routineName, hasReturnValue, argumentCount);
    }

    private static List<String> splitArguments(String argumentList) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int index = 0; index < argumentList.length(); index++) {
            char ch = argumentList.charAt(index);
            if (ch == '\'') {
                current.append(ch);
                if (inString && index + 1 < argumentList.length() && argumentList.charAt(index + 1) == '\'') {
                    current.append(argumentList.charAt(++index));
                    continue;
                }
                inString = !inString;
                continue;
            }
            if (!inString && ch == ',') {
                arguments.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        arguments.add(current.toString());
        return arguments;
    }

    String nativeSql() {
        return nativeSql;
    }

    String schemaName() {
        return schemaName;
    }

    String routineName() {
        return routineName;
    }

    boolean hasReturnValue() {
        return hasReturnValue;
    }

    int parameterCount() {
        return argumentCount + (hasReturnValue ? 1 : 0);
    }

    int argumentCount() {
        return argumentCount;
    }

    boolean isReturnParameter(int parameterIndex) {
        return hasReturnValue && parameterIndex == 1;
    }

    int toInternalParameterIndex(int parameterIndex) throws SQLException {
        if (parameterIndex < 1 || parameterIndex > parameterCount()) {
            throw new SQLException("Parameter index out of range: " + parameterIndex);
        }
        if (isReturnParameter(parameterIndex)) {
            throw new SQLException("Return parameter cannot be used as an input parameter");
        }
        return hasReturnValue ? parameterIndex - 1 : parameterIndex;
    }

    int toExternalParameterIndex(int argumentOrdinal) {
        return hasReturnValue ? argumentOrdinal + 2 : argumentOrdinal + 1;
    }
}
