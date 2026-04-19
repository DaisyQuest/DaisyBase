package dev.javadb.engine;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.execution.Execution;
import dev.javadb.sql.SqlFrontend;
import dev.javadb.txn.Transactions;

import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class RoutineRuntime {
    private final Catalog.CatalogSnapshot catalogSnapshot;
    private final Execution.SequenceAllocator sequenceAllocator;
    private final SqlExecutor sqlExecutor;
    private final FunctionInvoker functionInvoker;

    RoutineRuntime(Catalog.CatalogSnapshot catalogSnapshot,
                   Execution.SequenceAllocator sequenceAllocator,
                   SqlExecutor sqlExecutor,
                   FunctionInvoker functionInvoker) {
        this.catalogSnapshot = Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        this.sequenceAllocator = Objects.requireNonNull(sequenceAllocator, "sequenceAllocator");
        this.sqlExecutor = Objects.requireNonNull(sqlExecutor, "sqlExecutor");
        this.functionInvoker = Objects.requireNonNull(functionInvoker, "functionInvoker");
    }

    CallOutcome executeCall(Catalog.RoutineDefinition routine, List<Common.Value> arguments,
                            Transactions.TransactionState transactionState) {
        ExecutionFrame frame = executeRoutine(routine, arguments, transactionState);
        if (routine.kind() == Catalog.RoutineKind.FUNCTION) {
            return new CallOutcome("CALL", new Common.TupleBatch(
                    List.of(new Common.ResultColumn("RETURN_VALUE", routine.returnType())),
                    List.of(new Common.ResultRow(List.of(frame.returnValue())))));
        }
        List<Common.ResultColumn> columns = new ArrayList<>();
        List<Common.Value> values = new ArrayList<>();
        for (Catalog.RoutineParameter parameter : routine.parameters()) {
            if (parameter.mode() == Catalog.ParameterMode.IN) {
                continue;
            }
            columns.add(new Common.ResultColumn(parameter.name(), parameter.type()));
            values.add(frame.variables().get(parameter.name()));
        }
        Common.TupleBatch batch = columns.isEmpty()
                ? Common.TupleBatch.empty()
                : new Common.TupleBatch(columns, List.of(new Common.ResultRow(values)));
        return new CallOutcome("CALL", batch);
    }

    CallOutcome executeCall(Catalog.RoutineDefinition routine, List<SqlFrontend.Expression> arguments,
                            Transactions.TransactionState transactionState, Common.SourceSpan span) {
        return executeCall(routine, materializeArguments(routine, arguments, transactionState, span), transactionState);
    }

    Common.Value invokeFunction(Catalog.RoutineDefinition routine, List<Common.Value> arguments,
                                Transactions.TransactionState transactionState) {
        return executeRoutine(routine, arguments, transactionState).returnValue();
    }

    private List<Common.Value> materializeArguments(Catalog.RoutineDefinition routine, List<SqlFrontend.Expression> arguments,
                                                    Transactions.TransactionState transactionState, Common.SourceSpan span) {
        if (routine.parameters().size() != arguments.size()) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Routine " + routine.name().toSql() + " expects " + routine.parameters().size()
                            + " argument(s) but received " + arguments.size(), span);
        }
        List<Common.Value> values = new ArrayList<>(arguments.size());
        for (int index = 0; index < arguments.size(); index++) {
            Catalog.RoutineParameter parameter = routine.parameters().get(index);
            values.add(switch (parameter.mode()) {
                case OUT -> Common.Value.nullValue(parameter.type());
                case IN, INOUT -> Common.Values.coerce(
                        evaluateExpression(arguments.get(index), Map.of(), transactionState),
                        parameter.type(), parameter.precision(), parameter.scale());
            });
        }
        return values;
    }

    private ExecutionFrame executeRoutine(Catalog.RoutineDefinition routine, List<Common.Value> arguments,
                                          Transactions.TransactionState transactionState) {
        if (routine.parameters().size() != arguments.size()) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Routine " + routine.name().toSql() + " expects " + routine.parameters().size()
                            + " argument(s) but received " + arguments.size());
        }
        Map<String, Common.Value> variables = new LinkedHashMap<>();
        for (int index = 0; index < routine.parameters().size(); index++) {
            Catalog.RoutineParameter parameter = routine.parameters().get(index);
            Common.Value value = switch (parameter.mode()) {
                case IN, INOUT -> Common.Values.coerce(arguments.get(index), parameter.type(),
                        parameter.precision(), parameter.scale());
                case OUT -> Common.Value.nullValue(parameter.type());
            };
            variables.put(parameter.name(), value);
        }
        for (RoutineStep step : compile(routine.bodySql())) {
            switch (step) {
                case NoOpStep ignored -> {
                }
                case AssignmentStep assignment -> {
                    Catalog.RoutineParameter parameter = requireParameter(routine, assignment.variable());
                    Common.Value value = Common.Values.coerce(
                            evaluateExpression(assignment.expression(), variables, transactionState),
                            parameter.type(), parameter.precision(), parameter.scale());
                    variables.put(parameter.name(), value);
                }
                case ReturnStep returnStep -> {
                    if (routine.kind() != Catalog.RoutineKind.FUNCTION) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                "RETURN is only valid inside functions");
                    }
                    return new ExecutionFrame(variables,
                            Common.Values.coerce(evaluateExpression(returnStep.expression(), variables, transactionState),
                                    routine.returnType(), routine.returnPrecision(), routine.returnScale()));
                }
                case SqlStep sqlStep -> sqlExecutor.execute(substituteStatement(sqlStep.statement(), variables), transactionState);
            }
        }
        if (routine.kind() == Catalog.RoutineKind.FUNCTION) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Function " + routine.name().toSql() + " did not return a value");
        }
        return new ExecutionFrame(variables, null);
    }

    private Catalog.RoutineParameter requireParameter(Catalog.RoutineDefinition routine, String name) {
        return routine.parameters().stream()
                .filter(parameter -> parameter.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Unknown routine variable " + name + " in " + routine.name().toSql()));
    }

    private Common.Value evaluateExpression(SqlFrontend.Expression expression, Map<String, Common.Value> variables,
                                            Transactions.TransactionState transactionState) {
        return switch (expression) {
            case SqlFrontend.IdentifierExpression identifier -> {
                String name = identifier.qualifiedName().name().toLowerCase(Locale.ROOT);
                Common.Value value = variables.get(name);
                if (value == null) {
                    throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "Unknown routine variable " + name, identifier.span());
                }
                yield value;
            }
            case SqlFrontend.LiteralExpression literal -> literal.value();
            case SqlFrontend.BinaryExpression binary -> evaluateBinary(binary, variables, transactionState);
            case SqlFrontend.FunctionCallExpression functionCall -> evaluateFunction(functionCall, variables, transactionState);
            case SqlFrontend.NextValueExpression nextValue -> catalogSnapshot.sequence(Catalog.QualifiedName.from(nextValue.sequenceName()))
                    .map(sequenceAllocator::nextValue)
                    .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "Unknown sequence " + Catalog.QualifiedName.from(nextValue.sequenceName()).toSql(), nextValue.span()));
            case SqlFrontend.ParameterExpression parameter -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                    "Prepared-statement parameters are not valid in routine bodies", parameter.span());
            case SqlFrontend.StarExpression star -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Star is not valid in routine expressions", star.span());
        };
    }

    private Common.Value evaluateBinary(SqlFrontend.BinaryExpression binary, Map<String, Common.Value> variables,
                                        Transactions.TransactionState transactionState) {
        Common.Value left = evaluateExpression(binary.left(), variables, transactionState);
        Common.Value right = evaluateExpression(binary.right(), variables, transactionState);
        Common.DataType type = binary.operator() == SqlFrontend.BinaryOperator.AND
                || binary.operator() == SqlFrontend.BinaryOperator.OR
                || binary.operator() == SqlFrontend.BinaryOperator.EQ
                || binary.operator() == SqlFrontend.BinaryOperator.NEQ
                || binary.operator() == SqlFrontend.BinaryOperator.LT
                || binary.operator() == SqlFrontend.BinaryOperator.LTE
                || binary.operator() == SqlFrontend.BinaryOperator.GT
                || binary.operator() == SqlFrontend.BinaryOperator.GTE
                ? Common.DataType.BOOLEAN
                : numericType(left.type(), right.type(), binary.span());
        return switch (binary.operator()) {
            case EQ -> Common.Value.bool(Common.Values.compare(left, right, "="));
            case NEQ -> Common.Value.bool(Common.Values.compare(left, right, "!="));
            case LT -> Common.Value.bool(Common.Values.compare(left, right, "<"));
            case LTE -> Common.Value.bool(Common.Values.compare(left, right, "<="));
            case GT -> Common.Value.bool(Common.Values.compare(left, right, ">"));
            case GTE -> Common.Value.bool(Common.Values.compare(left, right, ">="));
            case AND -> Common.Value.bool(and(left, right));
            case OR -> Common.Value.bool(or(left, right));
            case ADD -> add(left, right, type);
            case SUB -> subtract(left, right, type);
            case MUL -> multiply(left, right, type);
            case DIV -> divide(left, right, type);
        };
    }

    private Common.Value evaluateFunction(SqlFrontend.FunctionCallExpression functionCall, Map<String, Common.Value> variables,
                                          Transactions.TransactionState transactionState) {
        String name = functionCall.name().toUpperCase(Locale.ROOT);
        List<Common.Value> arguments = functionCall.arguments().stream()
                .map(argument -> evaluateExpression(argument, variables, transactionState))
                .toList();
        return switch (name) {
            case "LOWER" -> nullSafeText(arguments.getFirst(), String::toLowerCase);
            case "UPPER" -> nullSafeText(arguments.getFirst(), String::toUpperCase);
            case "LENGTH" -> {
                Common.Value argument = arguments.getFirst();
                yield argument == null || argument.isNull()
                        ? Common.Value.nullValue(Common.DataType.BIGINT)
                        : Common.Value.bigint((long) argument.asText().length());
            }
            case "ABS" -> {
                Common.Value argument = arguments.getFirst();
                if (argument == null || argument.isNull()) {
                    yield Common.Value.nullValue(argument == null ? Common.DataType.BIGINT : argument.type());
                }
                if (argument.type() == Common.DataType.INTEGER) {
                    yield Common.Value.integer(Math.abs(argument.asInt()));
                }
                if (argument.type() == Common.DataType.BIGINT) {
                    yield Common.Value.bigint(Math.abs(argument.asLong()));
                }
                yield Common.Value.decimal(argument.asDecimal().abs());
            }
            case "COALESCE", "NVL" -> {
                for (Common.Value argument : arguments) {
                    if (argument != null && !argument.isNull()) {
                        yield argument;
                    }
                }
                yield Common.Value.nullValue(arguments.getFirst().type());
            }
            case "TRIM" -> nullSafeText(arguments.getFirst(), String::strip);
            case "SUBSTR", "SUBSTRING" -> substring(arguments);
            case "REPLACE" -> replace(arguments);
            default -> invokeUserFunction(functionCall, arguments, transactionState);
        };
    }

    private Common.Value invokeUserFunction(SqlFrontend.FunctionCallExpression functionCall, List<Common.Value> arguments,
                                            Transactions.TransactionState transactionState) {
        Catalog.QualifiedName name = qualifiedRoutineName(functionCall.name());
        Catalog.RoutineDefinition routine = catalogSnapshot.routine(name)
                .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Unsupported routine function " + functionCall.name(), functionCall.span()));
        if (routine.kind() != Catalog.RoutineKind.FUNCTION) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    functionCall.name() + " is not a function", functionCall.span());
        }
        return functionInvoker.invoke(routine, arguments, transactionState);
    }

    private SqlFrontend.Statement substituteStatement(SqlFrontend.Statement statement, Map<String, Common.Value> variables) {
        return switch (statement) {
            case SqlFrontend.InsertStatement insert -> new SqlFrontend.InsertStatement(
                    insert.tableName(),
                    insert.columns(),
                    insert.rows().stream()
                            .map(row -> row.stream().map(expression -> substituteExpression(expression, variables)).toList())
                            .toList(),
                    insert.span());
            case SqlFrontend.UpdateStatement update -> new SqlFrontend.UpdateStatement(
                    update.tableName(),
                    update.assignments().stream()
                            .map(assignment -> new SqlFrontend.Assignment(assignment.column(),
                                    substituteExpression(assignment.expression(), variables), assignment.span()))
                            .toList(),
                    substituteNullable(update.where(), variables),
                    update.span());
            case SqlFrontend.DeleteStatement delete -> new SqlFrontend.DeleteStatement(
                    delete.tableName(),
                    substituteNullable(delete.where(), variables),
                    delete.span());
            case SqlFrontend.SelectStatement select -> new SqlFrontend.SelectStatement(
                    select.selectItems().stream()
                            .map(item -> new SqlFrontend.SelectItem(substituteExpression(item.expression(), variables), item.alias(), item.span()))
                            .toList(),
                    select.from(),
                    substituteNullable(select.where(), variables),
                    select.groupBy().stream().map(expression -> substituteExpression(expression, variables)).toList(),
                    substituteNullable(select.having(), variables),
                    select.orderBy().stream()
                            .map(order -> new SqlFrontend.OrderBy(substituteExpression(order.expression(), variables), order.ascending(), order.span()))
                            .toList(),
                    select.limit(),
                    select.span());
            case SqlFrontend.ExplainStatement explain -> new SqlFrontend.ExplainStatement(
                    substituteStatement(explain.statement(), variables), explain.span());
            case SqlFrontend.CallStatement call -> new SqlFrontend.CallStatement(
                    call.routineName(),
                    call.arguments().stream().map(expression -> substituteExpression(expression, variables)).toList(),
                    call.span());
            default -> statement;
        };
    }

    private SqlFrontend.Expression substituteNullable(SqlFrontend.Expression expression, Map<String, Common.Value> variables) {
        return expression == null ? null : substituteExpression(expression, variables);
    }

    private SqlFrontend.Expression substituteExpression(SqlFrontend.Expression expression, Map<String, Common.Value> variables) {
        return switch (expression) {
            case SqlFrontend.IdentifierExpression identifier -> {
                Common.Value value = identifier.qualifiedName().schema() == null
                        ? variables.get(identifier.qualifiedName().name().toLowerCase(Locale.ROOT))
                        : null;
                yield value == null ? expression : new SqlFrontend.LiteralExpression(value, identifier.span());
            }
            case SqlFrontend.LiteralExpression literal -> literal;
            case SqlFrontend.BinaryExpression binary -> new SqlFrontend.BinaryExpression(
                    substituteExpression(binary.left(), variables),
                    binary.operator(),
                    substituteExpression(binary.right(), variables),
                    binary.span());
            case SqlFrontend.FunctionCallExpression functionCall -> new SqlFrontend.FunctionCallExpression(
                    functionCall.name(),
                    functionCall.arguments().stream().map(argument -> substituteExpression(argument, variables)).toList(),
                    functionCall.span());
            case SqlFrontend.NextValueExpression nextValue -> nextValue;
            case SqlFrontend.ParameterExpression parameter -> parameter;
            case SqlFrontend.StarExpression star -> star;
        };
    }

    private List<RoutineStep> compile(String bodySql) {
        String normalized = bodySql == null ? "" : bodySql.strip();
        if (normalized.regionMatches(true, 0, "OR REPLACE", 0, "OR REPLACE".length())) {
            normalized = normalized.substring("OR REPLACE".length()).strip();
        }
        if (normalized.regionMatches(true, 0, "BEGIN", 0, "BEGIN".length())) {
            normalized = normalized.substring("BEGIN".length()).strip();
            if (normalized.toUpperCase(Locale.ROOT).endsWith("END")) {
                normalized = normalized.substring(0, normalized.length() - 3).strip();
            }
        }
        List<RoutineStep> steps = new ArrayList<>();
        for (String fragment : splitStatements(normalized)) {
            String statement = fragment.strip();
            if (statement.isBlank() || statement.equalsIgnoreCase("NULL")) {
                steps.add(new NoOpStep());
                continue;
            }
            if (statement.regionMatches(true, 0, "RETURN", 0, "RETURN".length())) {
                steps.add(new ReturnStep(SqlFrontend.parseExpressionFragment(statement.substring("RETURN".length()).strip())));
                continue;
            }
            int assignment = statement.indexOf(":=");
            if (assignment > 0) {
                steps.add(new AssignmentStep(statement.substring(0, assignment).strip().toLowerCase(Locale.ROOT),
                        SqlFrontend.parseExpressionFragment(statement.substring(assignment + 2).strip())));
                continue;
            }
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(statement);
            if (batch.statements().size() != 1) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Routine body statements must compile to exactly one statement");
            }
            steps.add(new SqlStep(batch.statements().getFirst()));
        }
        return steps;
    }

    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int depth = 0;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '\'') {
                current.append(ch);
                if (inString && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    current.append(sql.charAt(++index));
                    continue;
                }
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (ch == ';' && depth == 0) {
                    statements.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private Common.Value nullSafeText(Common.Value value, java.util.function.Function<String, String> mapper) {
        return value == null || value.isNull()
                ? Common.Value.nullValue(Common.DataType.TEXT)
                : Common.Value.text(mapper.apply(value.asText()));
    }

    private Common.Value substring(List<Common.Value> arguments) {
        Common.Value text = arguments.getFirst();
        Common.Value start = arguments.get(1);
        Common.Value length = arguments.size() > 2 ? arguments.get(2) : null;
        if (text == null || text.isNull() || start == null || start.isNull() || (length != null && length.isNull())) {
            return Common.Value.nullValue(Common.DataType.TEXT);
        }
        String raw = text.asText();
        int begin = substringStart(raw.length(), start.type() == Common.DataType.INTEGER ? start.asInt() : (int) start.asLong());
        int end = raw.length();
        if (length != null) {
            int requested = length.type() == Common.DataType.INTEGER ? length.asInt() : (int) length.asLong();
            if (requested <= 0) {
                return Common.Value.text("");
            }
            end = Math.min(raw.length(), begin + requested);
        }
        return Common.Value.text(raw.substring(begin, end));
    }

    private Common.Value replace(List<Common.Value> arguments) {
        Common.Value text = arguments.get(0);
        Common.Value search = arguments.get(1);
        Common.Value replacement = arguments.get(2);
        if (text == null || text.isNull() || search == null || search.isNull() || replacement == null || replacement.isNull()) {
            return Common.Value.nullValue(Common.DataType.TEXT);
        }
        return Common.Value.text(text.asText().replace(search.asText(), replacement.asText()));
    }

    private int substringStart(int inputLength, int sqlStart) {
        if (sqlStart > 0) {
            return Math.min(inputLength, sqlStart - 1);
        }
        if (sqlStart < 0) {
            return Math.max(0, inputLength + sqlStart);
        }
        return 0;
    }

    private Common.Value add(Common.Value left, Common.Value right, Common.DataType type) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return Common.Value.nullValue(type);
        }
        return switch (type) {
            case INTEGER -> Common.Value.integer(left.asInt() + right.asInt());
            case BIGINT -> Common.Value.bigint(left.asLong() + right.asLong());
            case DECIMAL -> Common.Value.decimal(left.asDecimal().add(right.asDecimal()));
            case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "ADD is only supported on numeric values");
        };
    }

    private Common.Value subtract(Common.Value left, Common.Value right, Common.DataType type) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return Common.Value.nullValue(type);
        }
        return switch (type) {
            case INTEGER -> Common.Value.integer(left.asInt() - right.asInt());
            case BIGINT -> Common.Value.bigint(left.asLong() - right.asLong());
            case DECIMAL -> Common.Value.decimal(left.asDecimal().subtract(right.asDecimal()));
            case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "SUB is only supported on numeric values");
        };
    }

    private Common.Value multiply(Common.Value left, Common.Value right, Common.DataType type) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return Common.Value.nullValue(type);
        }
        return switch (type) {
            case INTEGER -> Common.Value.integer(left.asInt() * right.asInt());
            case BIGINT -> Common.Value.bigint(left.asLong() * right.asLong());
            case DECIMAL -> Common.Value.decimal(left.asDecimal().multiply(right.asDecimal()));
            case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "MUL is only supported on numeric values");
        };
    }

    private Common.Value divide(Common.Value left, Common.Value right, Common.DataType type) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return Common.Value.nullValue(type);
        }
        return switch (type) {
            case INTEGER -> Common.Value.integer(left.asInt() / right.asInt());
            case BIGINT -> Common.Value.bigint(left.asLong() / right.asLong());
            case DECIMAL -> Common.Value.decimal(left.asDecimal().divide(right.asDecimal(), MathContext.DECIMAL128));
            case BOOLEAN, TEXT, DATE, TIME, TIMESTAMP -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "DIV is only supported on numeric values");
        };
    }

    private Boolean and(Common.Value left, Common.Value right) {
        Boolean leftValue = left == null || left.isNull() ? null : left.asBoolean();
        Boolean rightValue = right == null || right.isNull() ? null : right.asBoolean();
        if (Boolean.FALSE.equals(leftValue) || Boolean.FALSE.equals(rightValue)) {
            return false;
        }
        if (leftValue == null || rightValue == null) {
            return null;
        }
        return true;
    }

    private Boolean or(Common.Value left, Common.Value right) {
        Boolean leftValue = left == null || left.isNull() ? null : left.asBoolean();
        Boolean rightValue = right == null || right.isNull() ? null : right.asBoolean();
        if (Boolean.TRUE.equals(leftValue) || Boolean.TRUE.equals(rightValue)) {
            return true;
        }
        if (leftValue == null || rightValue == null) {
            return null;
        }
        return false;
    }

    private Common.DataType numericType(Common.DataType left, Common.DataType right, Common.SourceSpan span) {
        if (!left.numeric() || !right.numeric()) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Arithmetic operators require numeric operands", span);
        }
        if (left == Common.DataType.DECIMAL || right == Common.DataType.DECIMAL) {
            return Common.DataType.DECIMAL;
        }
        if (left == Common.DataType.BIGINT || right == Common.DataType.BIGINT) {
            return Common.DataType.BIGINT;
        }
        return Common.DataType.INTEGER;
    }

    private Catalog.QualifiedName qualifiedRoutineName(String functionName) {
        String[] parts = functionName.toLowerCase(Locale.ROOT).split("\\.", 2);
        return parts.length == 2
                ? new Catalog.QualifiedName(parts[0], parts[1])
                : new Catalog.QualifiedName("public", parts[0]);
    }

    interface SqlExecutor {
        EngineApi.StatementResult execute(SqlFrontend.Statement statement, Transactions.TransactionState transactionState);
    }

    interface FunctionInvoker {
        Common.Value invoke(Catalog.RoutineDefinition routine, List<Common.Value> arguments,
                            Transactions.TransactionState transactionState);
    }

    record CallOutcome(String commandTag, Common.TupleBatch batch) {
    }

    private record ExecutionFrame(Map<String, Common.Value> variables, Common.Value returnValue) {
    }

    private sealed interface RoutineStep permits NoOpStep, SqlStep, ReturnStep, AssignmentStep {
    }

    private record NoOpStep() implements RoutineStep {
    }

    private record SqlStep(SqlFrontend.Statement statement) implements RoutineStep {
    }

    private record ReturnStep(SqlFrontend.Expression expression) implements RoutineStep {
    }

    private record AssignmentStep(String variable, SqlFrontend.Expression expression) implements RoutineStep {
    }
}
