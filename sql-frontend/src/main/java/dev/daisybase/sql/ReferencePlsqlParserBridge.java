package dev.daisybase.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.daisybase.common.Common;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ReferencePlsqlParserBridge {
    private static final String MODE_PROPERTY = "daisybase.sql.referenceParser.mode";
    private static final String HOME_PROPERTY = "daisybase.sql.referenceParser.home";
    private static final String PYTHON_PROPERTY = "daisybase.sql.referenceParser.python";
    private static final String LEGACY_MODE_PROPERTY = "javadb.sql.referenceParser.mode";
    private static final String LEGACY_HOME_PROPERTY = "javadb.sql.referenceParser.home";
    private static final String LEGACY_PYTHON_PROPERTY = "javadb.sql.referenceParser.python";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private ReferencePlsqlParserBridge() {
    }

    static SqlFrontend.StatementBatch tryPreferredParse(String sql) {
        if (!prefersReference(sql)) {
            return null;
        }
        return tryParse(sql, null);
    }

    static SqlFrontend.StatementBatch tryParse(String sql, Common.DatabaseException nativeFailure) {
        Mode mode = Mode.fromSettings();
        if (mode == Mode.DISABLED) {
            return null;
        }
        boolean plsqlShaped = looksLikePlsql(sql);
        boolean unsupportedNative = nativeFailure != null
                && (nativeFailure.code() == Common.ErrorCode.UNSUPPORTED_FEATURE
                || nativeFailure.code() == Common.ErrorCode.PARSE_ERROR);
        if (!plsqlShaped && !unsupportedNative) {
            return null;
        }
        Path parserHome = discoverParserHome();
        if (parserHome == null) {
            if (mode == Mode.REQUIRED || plsqlShaped) {
                throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Reference PL/SQL parser was required but no parser home was found");
            }
            return null;
        }
        List<String> pythonCommand = discoverPythonCommand(parserHome);
        if (pythonCommand == null) {
            if (mode == Mode.REQUIRED || plsqlShaped) {
                throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Reference PL/SQL parser was required but no usable Python runtime was found");
            }
            return null;
        }
        ParsedPayload payload = invoke(parserHome, pythonCommand, sql);
        if (!payload.error().isBlank()) {
            if (plsqlShaped || mode == Mode.REQUIRED) {
                throw new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR,
                        "Reference PL/SQL parser error: " + payload.error());
            }
            return null;
        }
        List<SqlFrontend.Statement> statements = translatePayload(payload.ast());
        if (statements.isEmpty()) {
            return null;
        }
        return new SqlFrontend.StatementBatch(statements);
    }

    public static boolean isUsableForTests() {
        Path parserHome = discoverParserHome();
        return parserHome != null && discoverPythonCommand(parserHome) != null;
    }

    private static ParsedPayload invoke(Path parserHome, List<String> pythonCommand, String sql) {
        List<String> command = new ArrayList<>(pythonCommand);
        command.add("-c");
        command.add(String.join("\n",
                "import json, sys",
                "from front_end.parser_adapter import parse_sql",
                "result = parse_sql(sys.stdin.read())",
                "print(json.dumps({'error': result.error, 'ast': result.ast}))"));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(parserHome.toFile());
        builder.environment().put("PYTHONPATH", parserHome.toString());
        try {
            Process process = builder.start();
            try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(sql);
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR,
                        "Reference PL/SQL parser failed: " + stderr.strip());
            }
            Map<String, Object> payload = OBJECT_MAPPER.readValue(stdout, MAP_TYPE);
            Object error = payload.get("error");
            Object ast = payload.get("ast");
            return new ParsedPayload(asText(error), asMap(ast));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                    "Failed to invoke reference PL/SQL parser", exception);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                    "Failed to invoke reference PL/SQL parser", exception);
        }
    }

    private static List<SqlFrontend.Statement> translatePayload(Map<String, Object> ast) {
        if (ast.isEmpty()) {
            return List.of();
        }
        if (!payloadErrors(ast).isEmpty()) {
            return List.of();
        }
        List<SqlFrontend.Statement> translated = new ArrayList<>();
        for (Map<String, Object> statement : asListOfMaps(ast.get("statements"))) {
            translated.addAll(translateStatement(statement));
        }
        return translated;
    }

    private static List<SqlFrontend.Statement> translateStatement(Map<String, Object> statement) {
        String type = asText(statement.get("type")).toUpperCase(Locale.ROOT);
        return switch (type) {
            case "CREATE_TABLE" -> List.of(translateCreateTable(statement));
            case "CREATE_INDEX" -> List.of(translateCreateIndex(statement));
            case "INSERT_STATEMENT" -> List.of(translateInsert(statement));
            case "UPDATE_STATEMENT" -> List.of(translateUpdate(statement));
            case "DELETE_STATEMENT" -> List.of(translateDelete(statement));
            case "QUERY_EXPRESSION" -> List.of(translateQueryExpression(statement));
            case "ANONYMOUS_BLOCK" -> translateAnonymousBlock(statement);
            default -> List.of(referenceStatement(statement));
        };
    }

    private static List<SqlFrontend.Statement> translateAnonymousBlock(Map<String, Object> statement) {
        if (!asListOfMaps(statement.get("declarations")).isEmpty()
                || statement.get("exception") != null
                || statement.get("end_label") != null) {
            return List.of(referenceStatement(statement));
        }
        List<SqlFrontend.Statement> translated = new ArrayList<>();
        for (Map<String, Object> child : asListOfMaps(statement.get("statements"))) {
            translated.addAll(translateStatement(child));
        }
        return translated;
    }

    private static SqlFrontend.Statement translateCreateTable(Map<String, Object> statement) {
        String tableName = asText(statement.get("table_name"));
        List<SqlFrontend.ColumnDefinition> columns = new ArrayList<>();
        for (Map<String, Object> column : asListOfMaps(statement.get("columns"))) {
            String name = asText(column.get("name")).toLowerCase(Locale.ROOT);
            String typeName = firstTypeToken(column.get("data_type"));
            boolean notNull = false;
            boolean primaryKey = false;
            boolean unique = false;
            SqlFrontend.Expression checkExpression = null;
            for (Map<String, Object> constraint : asListOfMaps(column.get("constraints"))) {
                String constraintType = asText(constraint.get("type")).toUpperCase(Locale.ROOT);
                switch (constraintType) {
                    case "NOT_NULL_CONSTRAINT" -> notNull = true;
                    case "PRIMARY_KEY_CONSTRAINT" -> {
                        primaryKey = true;
                        unique = true;
                        notNull = true;
                    }
                    case "UNIQUE_CONSTRAINT" -> unique = true;
                    case "CHECK_CONSTRAINT" -> checkExpression = translateConstraintExpression(firstPresent(constraint, "condition", "expression"));
                    default -> throw unsupportedForBridge("Unsupported column constraint in reference bridge: " + constraintType);
                }
            }
            columns.add(new SqlFrontend.ColumnDefinition(name, typeName, notNull, primaryKey, unique, checkExpression, null, Common.SourceSpan.NONE));
        }
        for (Map<String, Object> tableConstraint : asListOfMaps(statement.get("table_constraints"))) {
            String constraintType = asText(tableConstraint.get("type")).toUpperCase(Locale.ROOT);
            List<String> constrainedColumns = asStringList(tableConstraint.get("columns"));
            switch (constraintType) {
                case "PRIMARY_KEY_CONSTRAINT" -> applySingleColumnConstraint(columns, constrainedColumns, true, true, true, null);
                case "UNIQUE_CONSTRAINT" -> applySingleColumnConstraint(columns, constrainedColumns, false, true, false, null);
                case "CHECK_CONSTRAINT" -> {
                    SqlFrontend.Expression expression = translateConstraintExpression(firstPresent(tableConstraint, "condition", "expression"));
                    if (constrainedColumns.isEmpty()) {
                        constrainedColumns = inferSingleColumnConstraint(expression);
                    }
                    applySingleColumnConstraint(columns, constrainedColumns, false, false, false, expression);
                }
                default -> throw unsupportedForBridge("Unsupported table constraint in reference bridge: " + constraintType);
            }
        }
        return new SqlFrontend.CreateTableStatement(toQualifiedName(tableName), columns, Common.SourceSpan.NONE);
    }

    private static SqlFrontend.Statement translateCreateIndex(Map<String, Object> statement) {
        return new SqlFrontend.CreateIndexStatement(
                asText(statement.get("index_name")).toLowerCase(Locale.ROOT),
                toQualifiedName(asText(statement.get("table_name"))),
                asStringList(statement.get("columns")),
                Boolean.TRUE.equals(statement.get("unique")),
                Common.SourceSpan.NONE);
    }

    private static SqlFrontend.Statement translateInsert(Map<String, Object> statement) {
        if (!"SINGLE".equalsIgnoreCase(asText(statement.get("mode")))) {
            throw unsupportedForBridge("Only single-target INSERT statements are supported in the reference bridge");
        }
        List<Map<String, Object>> targets = asListOfMaps(statement.get("targets"));
        if (targets.size() != 1) {
            throw unsupportedForBridge("Only single-target INSERT statements are supported in the reference bridge");
        }
        Map<String, Object> target = targets.getFirst();
        Map<String, Object> source = asMap(target.get("source"));
        if (!"VALUES_CLAUSE".equalsIgnoreCase(asText(source.get("type")))) {
            throw unsupportedForBridge("Only VALUES-based INSERT statements are supported in the reference bridge");
        }
        List<SqlFrontend.Expression> row = new ArrayList<>();
        for (Map<String, Object> value : asListOfMaps(source.get("values"))) {
            row.add(translateReferenceExpression(value));
        }
        return new SqlFrontend.InsertStatement(
                toQualifiedName(asText(target.get("table"))),
                asStringList(target.get("columns")),
                List.of(row),
                Common.SourceSpan.NONE);
    }

    private static SqlFrontend.Statement translateUpdate(Map<String, Object> statement) {
        List<SqlFrontend.Assignment> assignments = new ArrayList<>();
        for (Map<String, Object> assignment : asListOfMaps(statement.get("assignments"))) {
            assignments.add(new SqlFrontend.Assignment(
                    identifierName(asMap(assignment.get("target"))),
                    translateReferenceExpression(asMap(assignment.get("value"))),
                    Common.SourceSpan.NONE));
        }
        SqlFrontend.Expression where = null;
        if (statement.get("where_clause") != null) {
            where = translateReferenceExpression(asMap(asMap(statement.get("where_clause")).get("condition")));
        }
        return new SqlFrontend.UpdateStatement(toQualifiedName(asText(statement.get("table"))), assignments, where, Common.SourceSpan.NONE);
    }

    private static SqlFrontend.Statement translateDelete(Map<String, Object> statement) {
        SqlFrontend.Expression where = null;
        if (statement.get("where_clause") != null) {
            where = translateReferenceExpression(asMap(asMap(statement.get("where_clause")).get("condition")));
        }
        return new SqlFrontend.DeleteStatement(toQualifiedName(asText(statement.get("table"))), where, Common.SourceSpan.NONE);
    }

    private static SqlFrontend.Statement translateQueryExpression(Map<String, Object> statement) {
        return referenceStatement(statement);
    }

    private static SqlFrontend.ReferenceStatement referenceStatement(Map<String, Object> statement) {
        String type = asText(statement.get("type"));
        return new SqlFrontend.ReferenceStatement("PLSQL-REFERENCE", type, summarize(statement), statement, Common.SourceSpan.NONE);
    }

    private static String summarize(Map<String, Object> statement) {
        return firstNonBlank(
                asText(statement.get("table_name")),
                asText(statement.get("table")),
                asText(statement.get("index_name")),
                asText(statement.get("name")),
                asText(statement.get("type")));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static void applySingleColumnConstraint(List<SqlFrontend.ColumnDefinition> columns, List<String> constrainedColumns,
                                                    boolean primaryKey, boolean unique, boolean notNull,
                                                    SqlFrontend.Expression checkExpression) {
        if (constrainedColumns.size() != 1) {
            throw unsupportedForBridge("Composite table constraints are not supported in the reference bridge");
        }
        String target = constrainedColumns.getFirst().toLowerCase(Locale.ROOT);
        for (int index = 0; index < columns.size(); index++) {
            SqlFrontend.ColumnDefinition column = columns.get(index);
            if (!column.name().equalsIgnoreCase(target)) {
                continue;
            }
            columns.set(index, new SqlFrontend.ColumnDefinition(
                    column.name(),
                    column.typeName(),
                    column.notNull() || notNull,
                    column.primaryKey() || primaryKey,
                    column.unique() || unique || primaryKey,
                    checkExpression != null ? checkExpression : column.checkExpression(),
                    column.identityDefinition(),
                    column.span()));
            return;
        }
        throw unsupportedForBridge("Constraint references unknown column " + target);
    }

    private static List<String> inferSingleColumnConstraint(SqlFrontend.Expression expression) {
        List<String> identifiers = new ArrayList<>();
        collectIdentifiers(expression, identifiers);
        return identifiers.stream().distinct().limit(2).toList();
    }

    private static void collectIdentifiers(SqlFrontend.Expression expression, List<String> identifiers) {
        switch (expression) {
            case null -> {
            }
            case SqlFrontend.IdentifierExpression identifier -> identifiers.add(identifier.qualifiedName().name().toLowerCase(Locale.ROOT));
            case SqlFrontend.BinaryExpression binary -> {
                collectIdentifiers(binary.left(), identifiers);
                collectIdentifiers(binary.right(), identifiers);
            }
                case SqlFrontend.FunctionCallExpression functionCall -> functionCall.arguments().forEach(argument -> collectIdentifiers(argument, identifiers));
                case SqlFrontend.NextValueExpression nextValue -> identifiers.add(nextValue.sequenceName().name().toLowerCase(Locale.ROOT));
                case SqlFrontend.ParameterExpression ignored -> {
                }
                case SqlFrontend.LiteralExpression ignored -> {
                }
                case SqlFrontend.StarExpression ignored -> {
                }
        }
    }

    private static SqlFrontend.Expression translateReferenceExpression(Map<String, Object> expression) {
        String type = asText(expression.get("type")).toUpperCase(Locale.ROOT);
        return switch (type) {
            case "IDENTIFIER" -> new SqlFrontend.IdentifierExpression(
                    new SqlFrontend.QualifiedName(null, identifierName(expression)),
                    Common.SourceSpan.NONE);
            case "QUALIFIED_IDENTIFIER" -> {
                List<String> parts = asStringList(expression.get("parts"));
                if (parts.size() != 2) {
                    throw unsupportedForBridge("Only two-part qualified identifiers are supported in the reference bridge");
                }
                yield new SqlFrontend.IdentifierExpression(
                        new SqlFrontend.QualifiedName(parts.get(0).toLowerCase(Locale.ROOT), parts.get(1).toLowerCase(Locale.ROOT)),
                        Common.SourceSpan.NONE);
            }
            case "LITERAL" -> new SqlFrontend.LiteralExpression(referenceLiteral(expression), Common.SourceSpan.NONE);
            case "BINARY_EXPRESSION" -> new SqlFrontend.BinaryExpression(
                    translateReferenceExpression(asMap(expression.get("left"))),
                    translateBinaryOperator(asText(expression.get("operator"))),
                    translateReferenceExpression(asMap(expression.get("right"))),
                    Common.SourceSpan.NONE);
            case "FUNCTION_CALL" -> new SqlFrontend.FunctionCallExpression(
                    functionName(expression),
                    translateReferenceArguments(expression.get("arguments")),
                    Common.SourceSpan.NONE);
            case "STAR" -> new SqlFrontend.StarExpression(Common.SourceSpan.NONE);
            default -> throw unsupportedForBridge("Unsupported expression in reference bridge: " + type);
        };
    }

    private static List<SqlFrontend.Expression> translateReferenceArguments(Object arguments) {
        List<SqlFrontend.Expression> translated = new ArrayList<>();
        for (Map<String, Object> argument : asListOfMaps(arguments)) {
            translated.add(translateReferenceExpression(argument));
        }
        return translated;
    }

    private static String functionName(Map<String, Object> expression) {
        Object raw = expression.get("name");
        if (raw instanceof Map<?, ?> map) {
            return asText(map.get("value")).toUpperCase(Locale.ROOT);
        }
        return asText(raw).toUpperCase(Locale.ROOT);
    }

    private static SqlFrontend.BinaryOperator translateBinaryOperator(String operator) {
        return switch (operator) {
            case "=" -> SqlFrontend.BinaryOperator.EQ;
            case "!=", "<>" -> SqlFrontend.BinaryOperator.NEQ;
            case "<" -> SqlFrontend.BinaryOperator.LT;
            case "<=" -> SqlFrontend.BinaryOperator.LTE;
            case ">" -> SqlFrontend.BinaryOperator.GT;
            case ">=" -> SqlFrontend.BinaryOperator.GTE;
            case "AND" -> SqlFrontend.BinaryOperator.AND;
            case "OR" -> SqlFrontend.BinaryOperator.OR;
            case "+" -> SqlFrontend.BinaryOperator.ADD;
            case "-" -> SqlFrontend.BinaryOperator.SUB;
            case "*" -> SqlFrontend.BinaryOperator.MUL;
            case "/" -> SqlFrontend.BinaryOperator.DIV;
            default -> throw unsupportedForBridge("Unsupported operator in reference bridge: " + operator);
        };
    }

    private static Common.Value referenceLiteral(Map<String, Object> expression) {
        String literalType = asText(expression.get("literal_type")).toUpperCase(Locale.ROOT);
        String value = asText(expression.get("value"));
        return switch (literalType) {
            case "NUMERIC_LITERAL" -> value.contains(".")
                    ? Common.Value.bigint(Long.parseLong(value.substring(0, value.indexOf('.'))))
                    : Common.Value.bigint(Long.parseLong(value));
            case "STRING_LITERAL" -> Common.Value.text(unquote(value));
            case "NULL_LITERAL" -> Common.Value.text(null);
            case "BOOLEAN_LITERAL" -> Common.Value.bool(Boolean.parseBoolean(value.toLowerCase(Locale.ROOT)));
            default -> throw unsupportedForBridge("Unsupported literal in reference bridge: " + literalType);
        };
    }

    private static String unquote(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        if (text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    private static String firstTypeToken(Object dataType) {
        List<String> tokens = asStringList(asMap(dataType).get("tokens"));
        if (tokens.isEmpty()) {
            throw unsupportedForBridge("Reference parser data type is missing tokens");
        }
        return tokens.getFirst().toLowerCase(Locale.ROOT);
    }

    private static SqlFrontend.QualifiedName toQualifiedName(String text) {
        String[] parts = text.split("\\.", 2);
        if (parts.length == 2) {
            return new SqlFrontend.QualifiedName(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT));
        }
        return new SqlFrontend.QualifiedName(null, text.toLowerCase(Locale.ROOT));
    }

    private static String identifierName(Map<String, Object> expression) {
        return asText(expression.get("value")).toLowerCase(Locale.ROOT);
    }

    private static List<String> payloadErrors(Map<String, Object> ast) {
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> error : asListOfMaps(ast.get("errors"))) {
            messages.add(asText(error.get("message")));
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object value) {
        if (value == null) {
            return List.of();
        }
        List<?> raw = (List<?>) value;
        List<Map<String, Object>> maps = new ArrayList<>(raw.size());
        for (Object item : raw) {
            maps.add(asMap(item));
        }
        return maps;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return (Map<String, Object>) value;
    }

    private static Object firstPresent(Map<String, Object> node, String... keys) {
        for (String key : keys) {
            Object value = node.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                return value;
            }
            if (value instanceof List<?> list && !list.isEmpty()) {
                return value;
            }
        }
        return Map.of();
    }

    private static SqlFrontend.Expression translateConstraintExpression(Object rawExpression) {
        if (rawExpression instanceof Map<?, ?> map) {
            return translateReferenceExpression(asMap(map));
        }
        if (rawExpression instanceof List<?> tokens && !tokens.isEmpty()) {
            String sql = tokens.stream().map(ReferencePlsqlParserBridge::asText).reduce((left, right) -> left + " " + right).orElse("");
            return SqlFrontend.parseExpressionFragment(sql);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<?> raw = (List<?>) value;
        List<String> values = new ArrayList<>(raw.size());
        for (Object item : raw) {
            values.add(asText(item).toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private static String asText(Object value) {
        return value == null ? "" : Objects.toString(value, "");
    }

    private static Common.DatabaseException unsupportedForBridge(String message) {
        return new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, message);
    }

    private static Path discoverParserHome() {
        String configuredHome = firstConfiguredProperty(HOME_PROPERTY, LEGACY_HOME_PROPERTY);
        if (configuredHome != null) {
            if (configuredHome.isBlank()) {
                return null;
            }
            Path configured = Path.of(configuredHome);
            if (Files.isDirectory(configured)) {
                return configured.toAbsolutePath().normalize();
            }
            return null;
        }
        for (Path cursor = Path.of("").toAbsolutePath(); cursor != null; cursor = cursor.getParent()) {
            Path sibling = cursor.resolveSibling("PLSQL-Parser");
            if (Files.isDirectory(sibling)) {
                return sibling.toAbsolutePath().normalize();
            }
            Path child = cursor.resolve("PLSQL-Parser");
            if (Files.isDirectory(child)) {
                return child.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static List<String> discoverPythonCommand(Path parserHome) {
        String configuredPython = firstConfiguredProperty(PYTHON_PROPERTY, LEGACY_PYTHON_PROPERTY);
        if (configuredPython != null && !configuredPython.isBlank()) {
            return List.of(configuredPython);
        }
        List<List<String>> candidates = new ArrayList<>();
        Path windowsVenv = parserHome.resolve(".venv").resolve("Scripts").resolve("python.exe");
        Path unixVenv = parserHome.resolve(".venv").resolve("bin").resolve("python");
        if (Files.exists(windowsVenv)) {
            candidates.add(List.of(windowsVenv.toString()));
        }
        if (Files.exists(unixVenv)) {
            candidates.add(List.of(unixVenv.toString()));
        }
        candidates.add(List.of("py", "-3"));
        candidates.add(List.of("python3"));
        candidates.add(List.of("python"));
        for (List<String> candidate : candidates) {
            if (canRun(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canRun(List<String> command) {
        try {
            List<String> versionCommand = new ArrayList<>(command);
            versionCommand.add("--version");
            Process process = new ProcessBuilder(versionCommand).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean prefersReference(String sql) {
        String normalized = sql == null ? "" : sql.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (normalized.startsWith("BEGIN") && normalized.contains(" END")) {
            return true;
        }
        return looksLikePlsql(sql);
    }

    private static boolean looksLikePlsql(String sql) {
        String normalized = sql == null ? "" : sql.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return normalized.startsWith("DECLARE")
                || normalized.startsWith("BEGIN")
                || normalized.startsWith("CREATE OR REPLACE PACKAGE")
                || normalized.startsWith("CREATE OR REPLACE TRIGGER")
                || normalized.startsWith("CREATE PACKAGE")
                || normalized.startsWith("CREATE TRIGGER");
    }

    private record ParsedPayload(String error, Map<String, Object> ast) {
    }

    enum Mode {
        DISABLED,
        AUTO,
        REQUIRED;

        static Mode fromSettings() {
            String raw = firstConfiguredProperty(MODE_PROPERTY, LEGACY_MODE_PROPERTY);
            raw = raw == null ? "disabled" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (raw) {
                case "", "disabled", "off", "false" -> DISABLED;
                case "auto" -> AUTO;
                case "required", "on", "true" -> REQUIRED;
                default -> DISABLED;
            };
        }
    }

    private static String firstConfiguredProperty(String... keys) {
        for (String key : keys) {
            String value = System.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
