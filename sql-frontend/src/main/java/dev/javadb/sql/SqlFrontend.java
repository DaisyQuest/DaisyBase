package dev.javadb.sql;

import dev.javadb.common.Common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SqlFrontend {
    private SqlFrontend() {
    }

    public static StatementBatch parseBatch(String sql) {
        StatementBatch preferred = ReferencePlsqlParserBridge.tryPreferredParse(sql);
        if (preferred != null) {
            return preferred;
        }
        try {
            return new Parser(sql).parseBatch();
        } catch (Common.DatabaseException nativeFailure) {
            StatementBatch bridged = ReferencePlsqlParserBridge.tryParse(sql, nativeFailure);
            if (bridged != null) {
                return bridged;
            }
            StatementBatch explained = tryExplainBridge(sql);
            if (explained != null) {
                return explained;
            }
            throw nativeFailure;
        }
    }

    private static StatementBatch tryExplainBridge(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.strip();
        if (!trimmed.regionMatches(true, 0, "EXPLAIN", 0, "EXPLAIN".length())) {
            return null;
        }
        String explainTarget = trimmed.substring("EXPLAIN".length()).strip();
        if (explainTarget.isBlank()) {
            return null;
        }
        StatementBatch bridged = ReferencePlsqlParserBridge.tryParse(explainTarget,
                new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "EXPLAIN bridge"));
        if (bridged == null || bridged.statements().size() != 1) {
            return null;
        }
        return new StatementBatch(List.of(new ExplainStatement(bridged.statements().getFirst(), Common.SourceSpan.NONE)));
    }

    public static Expression parseExpressionFragment(String sql) {
        return new Parser(sql).parseExpressionFragment();
    }

    public static String renderExpression(Expression expression) {
        return switch (expression) {
            case IdentifierExpression identifier -> identifier.qualifiedName().toSql();
            case LiteralExpression literal -> renderLiteral(literal.value());
            case BinaryExpression binary -> renderExpression(binary.left()) + " " + binary.operator().sql() + " " + renderExpression(binary.right());
            case FunctionCallExpression function -> function.name() + "(" + function.arguments().stream().map(SqlFrontend::renderExpression).reduce((a, b) -> a + ", " + b).orElse("") + ")";
            case NextValueExpression nextValue -> "NEXT VALUE FOR " + nextValue.sequenceName().toSql();
            case ParameterExpression ignored -> "?";
            case StarExpression ignored -> "*";
        };
    }

    private static String renderLiteral(Common.Value value) {
        if (value == null || value.isNull()) {
            return "NULL";
        }
        return switch (value.type()) {
            case INTEGER -> Integer.toString(value.asInt());
            case BIGINT -> Long.toString(value.asLong());
            case BOOLEAN -> Boolean.toString(value.asBoolean()).toUpperCase(Locale.ROOT);
            case TEXT -> "'" + value.asText().replace("'", "''") + "'";
            case DECIMAL -> value.asDecimal().stripTrailingZeros().toPlainString();
            case DATE -> "DATE '" + value.asDate() + "'";
            case TIME -> "TIME '" + value.asTime() + "'";
            case TIMESTAMP -> "TIMESTAMP '" + value.asTimestamp() + "'";
        };
    }

    public sealed interface Statement permits CreateSchemaStatement, CreateTableStatement, CreateIndexStatement, CreateSequenceStatement,
            CreateRoutineStatement, CallStatement, InsertStatement, SelectStatement, UpdateStatement, DeleteStatement, BeginStatement,
            CommitStatement, RollbackStatement, ExplainStatement, ReferenceStatement {
        Common.SourceSpan span();
    }

    public record StatementBatch(List<Statement> statements) {
        public StatementBatch {
            statements = List.copyOf(statements);
        }
    }

    public record QualifiedName(String schema, String name) {
        public QualifiedName {
            Objects.requireNonNull(name, "name");
        }

        public String schemaOrDefault() {
            return schema == null || schema.isBlank() ? "public" : schema;
        }

        public String toSql() {
            return (schema == null ? "" : schema + ".") + name;
        }
    }

    public enum IdentityGeneration {
        ALWAYS,
        BY_DEFAULT
    }

    public record SequenceOptions(Long startWith, Long incrementBy, Long minValue, Long maxValue,
                                  Integer cacheSize, boolean cycle) {
    }

    public record IdentityDefinition(IdentityGeneration generation, SequenceOptions options, Common.SourceSpan span) {
    }

    public enum RoutineKind {
        FUNCTION,
        PROCEDURE
    }

    public enum ParameterMode {
        IN,
        OUT,
        INOUT
    }

    public record RoutineParameter(String name, String typeName, ParameterMode mode, Integer typePrecision,
                                   Integer typeScale, Common.SourceSpan span) {
        public RoutineParameter(String name, String typeName, ParameterMode mode, Common.SourceSpan span) {
            this(name, typeName, mode, null, null, span);
        }
    }

    public record ColumnDefinition(String name, String typeName, boolean notNull, boolean primaryKey, boolean unique,
                                   Expression checkExpression, IdentityDefinition identityDefinition,
                                   Integer typePrecision, Integer typeScale, Common.SourceSpan span) {
        public ColumnDefinition(String name, String typeName, boolean notNull, boolean primaryKey, boolean unique,
                                Expression checkExpression, IdentityDefinition identityDefinition, Common.SourceSpan span) {
            this(name, typeName, notNull, primaryKey, unique, checkExpression, identityDefinition, null, null, span);
        }
    }

    public record OrderBy(Expression expression, boolean ascending, Common.SourceSpan span) {
    }

    public record Assignment(String column, Expression expression, Common.SourceSpan span) {
    }

    public record SelectItem(Expression expression, String alias, Common.SourceSpan span) {
    }

    public sealed interface Expression permits IdentifierExpression, LiteralExpression, BinaryExpression, FunctionCallExpression,
            NextValueExpression, ParameterExpression, StarExpression {
        Common.SourceSpan span();
    }

    public record IdentifierExpression(QualifiedName qualifiedName, Common.SourceSpan span) implements Expression {
    }

    public record LiteralExpression(Common.Value value, Common.SourceSpan span) implements Expression {
    }

    public record BinaryExpression(Expression left, BinaryOperator operator, Expression right, Common.SourceSpan span) implements Expression {
    }

    public record FunctionCallExpression(String name, List<Expression> arguments, Common.SourceSpan span) implements Expression {
        public FunctionCallExpression {
            arguments = List.copyOf(arguments);
        }
    }

    public record NextValueExpression(QualifiedName sequenceName, Common.SourceSpan span) implements Expression {
    }

    public record ParameterExpression(int index, Common.SourceSpan span) implements Expression {
    }

    public record StarExpression(Common.SourceSpan span) implements Expression {
    }

    public enum BinaryOperator {
        EQ("="),
        NEQ("!="),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        AND("AND"),
        OR("OR"),
        ADD("+"),
        SUB("-"),
        MUL("*"),
        DIV("/");

        private final String sql;

        BinaryOperator(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    public record CreateSchemaStatement(String schemaName, Common.SourceSpan span) implements Statement {
    }

    public record CreateTableStatement(QualifiedName tableName, List<ColumnDefinition> columns, Common.SourceSpan span) implements Statement {
        public CreateTableStatement {
            columns = List.copyOf(columns);
        }
    }

    public record CreateIndexStatement(String indexName, QualifiedName tableName, List<String> columns, boolean unique,
                                       Common.SourceSpan span) implements Statement {
        public CreateIndexStatement {
            columns = List.copyOf(columns);
        }
    }

    public record CreateSequenceStatement(QualifiedName sequenceName, SequenceOptions options, Common.SourceSpan span) implements Statement {
    }

    public record CreateRoutineStatement(RoutineKind kind, QualifiedName routineName, List<RoutineParameter> parameters,
                                         String returnTypeName, Integer returnTypePrecision, Integer returnTypeScale,
                                         String bodySql, Common.SourceSpan span) implements Statement {
        public CreateRoutineStatement {
            parameters = List.copyOf(parameters);
        }
    }

    private record ParsedTypeName(String name, Integer precision, Integer scale) {
    }

    public record CallStatement(QualifiedName routineName, List<Expression> arguments, Common.SourceSpan span) implements Statement {
        public CallStatement {
            arguments = List.copyOf(arguments);
        }
    }

    public record InsertStatement(QualifiedName tableName, List<String> columns, List<List<Expression>> rows,
                                  Common.SourceSpan span) implements Statement {
        public InsertStatement {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    public record SelectStatement(List<SelectItem> selectItems, QualifiedName from, Expression where, List<Expression> groupBy,
                                  Expression having, List<OrderBy> orderBy, Integer limit, Common.SourceSpan span) implements Statement {
        public SelectStatement {
            selectItems = List.copyOf(selectItems);
            groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
            orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        }
    }

    public record UpdateStatement(QualifiedName tableName, List<Assignment> assignments, Expression where,
                                  Common.SourceSpan span) implements Statement {
        public UpdateStatement {
            assignments = List.copyOf(assignments);
        }
    }

    public record DeleteStatement(QualifiedName tableName, Expression where, Common.SourceSpan span) implements Statement {
    }

    public record BeginStatement(Common.IsolationLevel isolationLevel, Common.SourceSpan span) implements Statement {
    }

    public record CommitStatement(Common.SourceSpan span) implements Statement {
    }

    public record RollbackStatement(Common.SourceSpan span) implements Statement {
    }

    public record ExplainStatement(Statement statement, Common.SourceSpan span) implements Statement {
    }

    public record ReferenceStatement(String dialect, String externalType, String summary, Map<String, Object> ast,
                                     Common.SourceSpan span) implements Statement {
        public ReferenceStatement {
            ast = ast == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ast));
        }
    }

    private enum TokenType {
        IDENTIFIER,
        NUMBER,
        STRING,
        SYMBOL,
        EOF
    }

    private record Token(TokenType type, String text, Common.SourceSpan span) {
        boolean isKeyword(String keyword) {
            return type == TokenType.IDENTIFIER && text.equalsIgnoreCase(keyword);
        }

        boolean isSymbol(String symbol) {
            return type == TokenType.SYMBOL && text.equals(symbol);
        }
    }

    private static final class Parser {
        private static final Set<String> DATA_TYPE_STOP_KEYWORDS = Set.of(
                "CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "REFERENCES", "NOT", "NULL",
                "GENERATED", "DEFAULT", "AS", "IS", "AUTHID", "PIPELINED", "RESULT_CACHE",
                "DETERMINISTIC", "PARALLEL_ENABLE", "AGGREGATE"
        );
        private static final Set<String> QUERY_BOUNDARY_KEYWORDS = Set.of(
                "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "FETCH", "OFFSET",
                "UNION", "INTERSECT", "MINUS", "EXCEPT"
        );
        private static final Set<String> JOIN_KEYWORDS = Set.of(
                "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "APPLY", "OUTER", "USING", "ON"
        );

        private final String input;
        private final List<Token> tokens;
        private int index;
        private int parameterIndex;

        private Parser(String input) {
            this.input = input == null ? "" : input;
            this.tokens = new Lexer(this.input).lex();
        }

        private enum ConstraintKind {
            NOT_NULL,
            NULLABLE,
            PRIMARY_KEY,
            UNIQUE,
            CHECK,
            FOREIGN_KEY
        }

        private enum ConstraintContext {
            COLUMN,
            TABLE
        }

        private record ConstraintSpec(ConstraintKind kind, List<String> columns, Expression checkExpression, Common.SourceSpan span) {
            private ConstraintSpec {
                columns = columns == null ? List.of() : List.copyOf(columns);
            }
        }

        private static final class SequenceOptionBuilder {
            private Long startWith = 1L;
            private Long incrementBy = 1L;
            private Long minValue;
            private Long maxValue;
            private Integer cacheSize = 20;
            private boolean cycle;

            private SequenceOptions build() {
                return new SequenceOptions(startWith, incrementBy, minValue, maxValue, cacheSize, cycle);
            }
        }

        StatementBatch parseBatch() {
            List<Statement> statements = new ArrayList<>();
            while (peek().type() != TokenType.EOF) {
                if (matchSymbol(";")) {
                    continue;
                }
                statements.add(parseStatement());
                matchSymbol(";");
            }
            return new StatementBatch(statements);
        }

        Expression parseExpressionFragment() {
            Expression expression = parseExpression();
            if (peek().type() != TokenType.EOF) {
                throw error("Unexpected trailing tokens in expression fragment", peek().span());
            }
            return expression;
        }

        private Statement parseStatement() {
            Token start = peek();
            if (matchKeyword("CREATE")) {
                if (matchKeyword("OR")) {
                    expectKeyword("REPLACE");
                    if (matchKeyword("PROCEDURE")) {
                        return parseCreateRoutine(start, RoutineKind.PROCEDURE, true);
                    }
                    if (matchKeyword("FUNCTION")) {
                        return parseCreateRoutine(start, RoutineKind.FUNCTION, true);
                    }
                    throw unsupported("CREATE OR REPLACE is not supported yet", previous().span());
                }
                if (matchKeyword("PROCEDURE")) {
                    return parseCreateRoutine(start, RoutineKind.PROCEDURE, false);
                }
                if (matchKeyword("FUNCTION")) {
                    return parseCreateRoutine(start, RoutineKind.FUNCTION, false);
                }
                if (matchKeyword("PACKAGE") || matchKeyword("TRIGGER")) {
                    throw unsupported("PL/SQL CREATE objects are not supported yet", previous().span());
                }
                if (matchKeyword("SCHEMA")) {
                    String schemaName = parseIdentifier();
                    return new CreateSchemaStatement(schemaName, span(start, previous()));
                }
                if (matchKeyword("TABLE")) {
                    return parseCreateTable(start);
                }
                if (matchKeyword("MATERIALIZED")) {
                    expectKeyword("VIEW");
                    throw unsupported("CREATE MATERIALIZED VIEW is not supported yet", previous().span());
                }
                if (matchKeyword("VIEW")) {
                    throw unsupported("CREATE VIEW is not supported yet", previous().span());
                }
                if (matchKeyword("UNIQUE")) {
                    expectKeyword("INDEX");
                    return parseCreateIndex(start, true);
                }
                if (matchKeyword("INDEX")) {
                    return parseCreateIndex(start, false);
                }
                if (matchKeyword("SEQUENCE")) {
                    return parseCreateSequence(start);
                }
                if (matchKeyword("SYNONYM")) {
                    throw unsupported("CREATE SYNONYM is not supported yet", previous().span());
                }
                throw error("Expected SCHEMA, TABLE, INDEX, SEQUENCE, PROCEDURE, or FUNCTION after CREATE", peek().span());
            }
            if (matchKeyword("ALTER")) {
                throw unsupported("ALTER statements are not supported yet", previous().span());
            }
            if (matchKeyword("DROP")) {
                throw unsupported("DROP statements are not supported yet", previous().span());
            }
            if (matchKeyword("TRUNCATE")) {
                throw unsupported("TRUNCATE is not supported yet", previous().span());
            }
            if (matchKeyword("GRANT")) {
                throw unsupported("GRANT is not supported yet", previous().span());
            }
            if (matchKeyword("REVOKE")) {
                throw unsupported("REVOKE is not supported yet", previous().span());
            }
            if (matchKeyword("COMMENT")) {
                throw unsupported("COMMENT ON is not supported yet", previous().span());
            }
            if (matchKeyword("INSERT")) {
                expectKeyword("INTO");
                QualifiedName tableName = parseQualifiedName();
                List<String> columns = List.of();
                if (matchSymbol("(")) {
                    columns = parseIdentifierList();
                    expectSymbol(")");
                }
                expectKeyword("VALUES");
                List<List<Expression>> rows = new ArrayList<>();
                do {
                    expectSymbol("(");
                    List<Expression> values = new ArrayList<>();
                    do {
                        values.add(parseExpression());
                    } while (matchSymbol(","));
                    expectSymbol(")");
                    rows.add(values);
                } while (matchSymbol(","));
                if (peekKeyword("RETURNING")) {
                    throw unsupported("RETURNING is not supported yet", peek().span());
                }
                return new InsertStatement(tableName, columns, rows, span(start, previous()));
            }
            if (peekKeyword("WITH")) {
                throw unsupported("WITH queries are not supported yet", peek().span());
            }
            if (matchKeyword("SELECT")) {
                return parseSelect(start);
            }
            if (matchKeyword("UPDATE")) {
                QualifiedName tableName = parseQualifiedName();
                if (!peekKeyword("SET")) {
                    throw unsupported("UPDATE aliases and advanced UPDATE forms are not supported yet", peek().span());
                }
                expectKeyword("SET");
                List<Assignment> assignments = new ArrayList<>();
                do {
                    Token assignmentStart = peek();
                    String column = parseIdentifier();
                    expectSymbol("=");
                    assignments.add(new Assignment(column, parseExpression(), span(assignmentStart, previous())));
                } while (matchSymbol(","));
                Expression where = null;
                if (matchKeyword("WHERE")) {
                    where = parseExpression();
                }
                if (peekKeyword("RETURNING")) {
                    throw unsupported("RETURNING is not supported yet", peek().span());
                }
                return new UpdateStatement(tableName, assignments, where, span(start, previous()));
            }
            if (matchKeyword("DELETE")) {
                expectKeyword("FROM");
                QualifiedName tableName = parseQualifiedName();
                Expression where = null;
                if (matchKeyword("WHERE")) {
                    where = parseExpression();
                }
                if (peekKeyword("RETURNING")) {
                    throw unsupported("RETURNING is not supported yet", peek().span());
                }
                return new DeleteStatement(tableName, where, span(start, previous()));
            }
            if (matchKeyword("MERGE")) {
                throw unsupported("MERGE is not supported yet", previous().span());
            }
            if (matchKeyword("CALL")) {
                return parseCall(start);
            }
            if (matchKeyword("LOCK")) {
                throw unsupported("LOCK TABLE is not supported yet", previous().span());
            }
            if (matchKeyword("DECLARE")) {
                throw unsupported("PL/SQL blocks are not supported yet", previous().span());
            }
            if (matchKeyword("CASE")) {
                throw unsupported("Standalone PL/SQL CASE blocks are not supported yet", previous().span());
            }
            if (matchKeyword("IF") || matchKeyword("LOOP") || matchKeyword("WHILE") || matchKeyword("FOR")
                    || matchKeyword("EXIT") || matchKeyword("CONTINUE") || matchKeyword("GOTO")
                    || matchKeyword("OPEN") || matchKeyword("FETCH") || matchKeyword("CLOSE")
                    || matchKeyword("EXECUTE") || matchKeyword("NULL") || matchKeyword("RETURN")) {
                throw unsupported("PL/SQL control statements are not supported yet", previous().span());
            }
            if (matchKeyword("BEGIN")) {
                matchKeyword("TRANSACTION");
                List<String> isolationWords = new ArrayList<>();
                if (matchKeyword("ISOLATION")) {
                    expectKeyword("LEVEL");
                    isolationWords.add(parseIdentifier().toUpperCase(Locale.ROOT));
                    isolationWords.add(parseIdentifier().toUpperCase(Locale.ROOT));
                }
                return new BeginStatement(Common.IsolationLevel.fromSqlWords(isolationWords), span(start, previous()));
            }
            if (matchKeyword("COMMIT")) {
                return new CommitStatement(span(start, previous()));
            }
            if (matchKeyword("ROLLBACK")) {
                return new RollbackStatement(span(start, previous()));
            }
            if (matchKeyword("EXPLAIN")) {
                Statement statement = parseStatement();
                return new ExplainStatement(statement, span(start, previous()));
            }
            throw error("Unsupported statement start: " + peek().text(), peek().span());
        }

        private CreateTableStatement parseCreateTable(Token start) {
            QualifiedName tableName = parseQualifiedName();
            expectSymbol("(");
            List<ColumnDefinition> columns = new ArrayList<>();
            List<ConstraintSpec> tableConstraints = new ArrayList<>();
            while (!peek().isSymbol(")") && peek().type() != TokenType.EOF) {
                if (isConstraintStart()) {
                    tableConstraints.add(parseConstraint(ConstraintContext.TABLE));
                } else {
                    columns.add(parseColumnDefinition());
                }
                if (matchSymbol(",")) {
                    continue;
                }
                break;
            }
            expectSymbol(")");
            if (peekKeyword("PARTITION")) {
                throw unsupported("Partition clauses are not supported yet", peek().span());
            }
            applyTableConstraints(columns, tableConstraints);
            return new CreateTableStatement(tableName, columns, span(start, previous()));
        }

        private CreateIndexStatement parseCreateIndex(Token start, boolean unique) {
            String indexName = parseIdentifier();
            expectKeyword("ON");
            QualifiedName tableName = parseQualifiedName();
            expectSymbol("(");
            List<String> columns = parseIdentifierList();
            expectSymbol(")");
            return new CreateIndexStatement(indexName, tableName, columns, unique, span(start, previous()));
        }

        private CreateSequenceStatement parseCreateSequence(Token start) {
            QualifiedName sequenceName = parseQualifiedName();
            SequenceOptionBuilder options = new SequenceOptionBuilder();
            while (peek().type() != TokenType.EOF && !peek().isSymbol(";")) {
                if (matchKeyword("START")) {
                    expectKeyword("WITH");
                    options.startWith = parseSignedLong();
                    continue;
                }
                if (matchKeyword("INCREMENT")) {
                    expectKeyword("BY");
                    options.incrementBy = parseSignedLong();
                    continue;
                }
                if (matchKeyword("MINVALUE")) {
                    options.minValue = parseSignedLong();
                    continue;
                }
                if (matchKeyword("NO")) {
                    if (matchKeyword("MINVALUE")) {
                        options.minValue = null;
                        continue;
                    }
                    if (matchKeyword("MAXVALUE")) {
                        options.maxValue = null;
                        continue;
                    }
                    if (matchKeyword("CYCLE")) {
                        options.cycle = false;
                        continue;
                    }
                    if (matchKeyword("CACHE")) {
                        options.cacheSize = null;
                        continue;
                    }
                    throw error("Expected MINVALUE, MAXVALUE, CYCLE, or CACHE after NO", peek().span());
                }
                if (matchKeyword("MAXVALUE")) {
                    options.maxValue = parseSignedLong();
                    continue;
                }
                if (matchKeyword("CACHE")) {
                    options.cacheSize = Math.toIntExact(parseSignedLong());
                    continue;
                }
                if (matchKeyword("CYCLE")) {
                    options.cycle = true;
                    continue;
                }
                if (peekKeyword("ORDER") || peekKeyword("KEEP") || peekKeyword("GLOBAL") || peekKeyword("SESSION")) {
                    throw unsupported("Advanced sequence options are not supported yet", peek().span());
                }
                break;
            }
            return new CreateSequenceStatement(sequenceName, options.build(), span(start, previous()));
        }

        private CreateRoutineStatement parseCreateRoutine(Token start, RoutineKind kind, boolean orReplace) {
            QualifiedName routineName = parseQualifiedName();
            List<RoutineParameter> parameters = matchSymbol("(") ? parseRoutineParameters() : List.of();
            String returnTypeName = null;
            Integer returnTypePrecision = null;
            Integer returnTypeScale = null;
            if (kind == RoutineKind.FUNCTION) {
                expectKeyword("RETURN");
                ParsedTypeName returnType = parseTypeName();
                returnTypeName = returnType.name();
                returnTypePrecision = returnType.precision();
                returnTypeScale = returnType.scale();
            }
            if (matchKeyword("AUTHID")) {
                throw unsupported("Invoker-rights routines are not supported yet", previous().span());
            }
            if (matchKeyword("PIPELINED") || matchKeyword("RESULT_CACHE") || matchKeyword("DETERMINISTIC")
                    || matchKeyword("PARALLEL_ENABLE") || matchKeyword("AGGREGATE")) {
                throw unsupported("Advanced routine modifiers are not supported yet", previous().span());
            }
            if (!(matchKeyword("AS") || matchKeyword("IS"))) {
                throw error("Expected AS or IS in routine definition", peek().span());
            }
            List<Token> bodyTokens = consumeRoutineBodyTokens();
            String bodySql = normalizeTokens(bodyTokens);
            if (bodySql.isBlank()) {
                throw error("Routine body cannot be empty", previous().span());
            }
            if (orReplace) {
                bodySql = "OR REPLACE " + bodySql;
            }
            return new CreateRoutineStatement(kind, routineName, parameters, returnTypeName,
                    returnTypePrecision, returnTypeScale, bodySql, span(start, previous()));
        }

        private CallStatement parseCall(Token start) {
            QualifiedName routineName = parseQualifiedName();
            List<Expression> arguments = new ArrayList<>();
            if (matchSymbol("(")) {
                if (!matchSymbol(")")) {
                    do {
                        arguments.add(parseExpression());
                    } while (matchSymbol(","));
                    expectSymbol(")");
                }
            }
            return new CallStatement(routineName, arguments, span(start, previous()));
        }

        private ColumnDefinition parseColumnDefinition() {
            Token columnStart = peek();
            String name = parseIdentifier();
            ParsedTypeName parsedType = parseTypeName();
            String typeName = parsedType.name();
            boolean notNull = false;
            boolean primaryKey = false;
            boolean unique = false;
            Expression check = null;
            IdentityDefinition identityDefinition = null;
            while (isInlineConstraintStart() || peekKeyword("GENERATED")) {
                if (peekKeyword("GENERATED")) {
                    if (identityDefinition != null) {
                        throw error("Duplicate identity clause for column " + name, peek().span());
                    }
                    identityDefinition = parseIdentityDefinition();
                    notNull = true;
                    continue;
                }
                ConstraintSpec constraint = parseConstraint(ConstraintContext.COLUMN);
                switch (constraint.kind()) {
                    case NOT_NULL -> notNull = true;
                    case NULLABLE -> notNull = false;
                    case PRIMARY_KEY -> {
                        primaryKey = true;
                        unique = true;
                        notNull = true;
                    }
                    case UNIQUE -> unique = true;
                    case CHECK -> check = check == null ? constraint.checkExpression() : mergeCheckExpressions(check, constraint.checkExpression());
                    case FOREIGN_KEY -> throw unsupported("FOREIGN KEY constraints are not supported yet", constraint.span());
                }
            }
            return new ColumnDefinition(name, typeName, notNull, primaryKey, unique, check, identityDefinition,
                    parsedType.precision(), parsedType.scale(), span(columnStart, previous()));
        }

        private ParsedTypeName parseTypeName() {
            List<Token> typeTokens = collectTokensUntil(Set.of(",", ")"), DATA_TYPE_STOP_KEYWORDS, true);
            if (typeTokens.isEmpty()) {
                throw error("Expected data type but found " + peek().text(), peek().span());
            }
            String baseName = typeTokens.getFirst().text().toLowerCase(Locale.ROOT);
            Integer precision = null;
            Integer scale = null;
            if (baseName.equals("decimal") || baseName.equals("numeric")) {
                List<String> numericTokens = typeTokens.stream().map(Token::text).toList();
                if (numericTokens.size() >= 4 && "(".equals(numericTokens.get(1)) && ")".equals(numericTokens.getLast())) {
                    try {
                        precision = Integer.parseInt(numericTokens.get(2));
                        if (numericTokens.size() >= 6 && ",".equals(numericTokens.get(3))) {
                            scale = Integer.parseInt(numericTokens.get(4));
                        }
                    } catch (NumberFormatException ignored) {
                        precision = null;
                        scale = null;
                    }
                }
            }
            return new ParsedTypeName(baseName, precision, scale);
        }

        private ConstraintSpec parseConstraint(ConstraintContext context) {
            Token constraintStart = peek();
            if (matchKeyword("CONSTRAINT")) {
                parseIdentifier();
            }
            if (matchKeyword("NOT")) {
                expectKeyword("NULL");
                return new ConstraintSpec(ConstraintKind.NOT_NULL, List.of(), null, span(constraintStart, previous()));
            }
            if (matchKeyword("NULL")) {
                return new ConstraintSpec(ConstraintKind.NULLABLE, List.of(), null, span(constraintStart, previous()));
            }
            if (matchKeyword("PRIMARY")) {
                expectKeyword("KEY");
                List<String> columns = parseConstraintColumns(context);
                return new ConstraintSpec(ConstraintKind.PRIMARY_KEY, columns, null, span(constraintStart, previous()));
            }
            if (matchKeyword("UNIQUE")) {
                List<String> columns = parseConstraintColumns(context);
                return new ConstraintSpec(ConstraintKind.UNIQUE, columns, null, span(constraintStart, previous()));
            }
            if (matchKeyword("CHECK")) {
                expectSymbol("(");
                Expression expression = parseExpression();
                expectSymbol(")");
                return new ConstraintSpec(ConstraintKind.CHECK, List.of(), expression, span(constraintStart, previous()));
            }
            if (matchKeyword("FOREIGN")) {
                expectKeyword("KEY");
                parseConstraintColumns(context);
                expectKeyword("REFERENCES");
                parseQualifiedName();
                if (matchSymbol("(")) {
                    parseIdentifierList();
                    expectSymbol(")");
                }
                return new ConstraintSpec(ConstraintKind.FOREIGN_KEY, List.of(), null, span(constraintStart, previous()));
            }
            if (matchKeyword("REFERENCES")) {
                parseQualifiedName();
                if (matchSymbol("(")) {
                    parseIdentifierList();
                    expectSymbol(")");
                }
                return new ConstraintSpec(ConstraintKind.FOREIGN_KEY, List.of(), null, span(constraintStart, previous()));
            }
            throw error("Expected constraint type but found " + peek().text(), peek().span());
        }

        private List<String> parseConstraintColumns(ConstraintContext context) {
            if (context == ConstraintContext.COLUMN && !peek().isSymbol("(")) {
                return List.of();
            }
            expectSymbol("(");
            List<String> columns = parseIdentifierList();
            expectSymbol(")");
            return columns;
        }

        private void applyTableConstraints(List<ColumnDefinition> columns, List<ConstraintSpec> tableConstraints) {
            for (ConstraintSpec constraint : tableConstraints) {
                switch (constraint.kind()) {
                    case PRIMARY_KEY, UNIQUE -> {
                        String columnName = resolveSingleColumnConstraint(constraint);
                        int columnIndex = findColumnIndex(columns, columnName, constraint.span());
                        ColumnDefinition column = columns.get(columnIndex);
                        boolean primaryKey = column.primaryKey() || constraint.kind() == ConstraintKind.PRIMARY_KEY;
                        boolean unique = column.unique() || constraint.kind() == ConstraintKind.UNIQUE || constraint.kind() == ConstraintKind.PRIMARY_KEY;
                        boolean notNull = column.notNull() || constraint.kind() == ConstraintKind.PRIMARY_KEY;
                        columns.set(columnIndex, new ColumnDefinition(
                                column.name(),
                                column.typeName(),
                                notNull,
                                primaryKey,
                                unique,
                                column.checkExpression(),
                                column.identityDefinition(),
                                column.span()));
                    }
                    case CHECK -> {
                        String columnName = singleReferencedColumn(constraint.checkExpression(), constraint.span());
                        int columnIndex = findColumnIndex(columns, columnName, constraint.span());
                        ColumnDefinition column = columns.get(columnIndex);
                        Expression merged = column.checkExpression() == null
                                ? constraint.checkExpression()
                                : mergeCheckExpressions(column.checkExpression(), constraint.checkExpression());
                        columns.set(columnIndex, new ColumnDefinition(
                                column.name(),
                                column.typeName(),
                                column.notNull(),
                                column.primaryKey(),
                                column.unique(),
                                merged,
                                column.identityDefinition(),
                                column.span()));
                    }
                    case FOREIGN_KEY -> throw unsupported("FOREIGN KEY constraints are not supported yet", constraint.span());
                    case NOT_NULL, NULLABLE -> throw error("Table constraints cannot declare NULLability", constraint.span());
                }
            }
        }

        private IdentityDefinition parseIdentityDefinition() {
            Token start = peek();
            expectKeyword("GENERATED");
            IdentityGeneration generation;
            if (matchKeyword("ALWAYS")) {
                generation = IdentityGeneration.ALWAYS;
            } else {
                expectKeyword("BY");
                expectKeyword("DEFAULT");
                generation = IdentityGeneration.BY_DEFAULT;
            }
            expectKeyword("AS");
            expectKeyword("IDENTITY");
            SequenceOptionBuilder options = new SequenceOptionBuilder();
            if (matchSymbol("(")) {
                while (!matchSymbol(")")) {
                    if (matchKeyword("START")) {
                        expectKeyword("WITH");
                        options.startWith = parseSignedLong();
                    } else if (matchKeyword("INCREMENT")) {
                        expectKeyword("BY");
                        options.incrementBy = parseSignedLong();
                    } else if (matchKeyword("MINVALUE")) {
                        options.minValue = parseSignedLong();
                    } else if (matchKeyword("NO")) {
                        if (matchKeyword("MINVALUE")) {
                            options.minValue = null;
                        } else if (matchKeyword("MAXVALUE")) {
                            options.maxValue = null;
                        } else if (matchKeyword("CYCLE")) {
                            options.cycle = false;
                        } else if (matchKeyword("CACHE")) {
                            options.cacheSize = null;
                        } else {
                            throw error("Expected MINVALUE, MAXVALUE, CYCLE, or CACHE after NO", peek().span());
                        }
                    } else if (matchKeyword("MAXVALUE")) {
                        options.maxValue = parseSignedLong();
                    } else if (matchKeyword("CACHE")) {
                        options.cacheSize = Math.toIntExact(parseSignedLong());
                    } else if (matchKeyword("CYCLE")) {
                        options.cycle = true;
                    } else {
                        throw unsupported("Unsupported identity option " + peek().text(), peek().span());
                    }
                }
            }
            return new IdentityDefinition(generation, options.build(), span(start, previous()));
        }

        private List<RoutineParameter> parseRoutineParameters() {
            List<RoutineParameter> parameters = new ArrayList<>();
            if (matchSymbol(")")) {
                return parameters;
            }
            do {
                Token start = peek();
                String name = parseIdentifier();
                ParameterMode mode = ParameterMode.IN;
                if (matchKeyword("IN")) {
                    if (matchKeyword("OUT")) {
                        mode = ParameterMode.INOUT;
                    }
                } else if (matchKeyword("INOUT")) {
                    mode = ParameterMode.INOUT;
                } else if (matchKeyword("OUT")) {
                    mode = ParameterMode.OUT;
                }
                ParsedTypeName parsedType = parseTypeName();
                parameters.add(new RoutineParameter(name, parsedType.name(), mode,
                        parsedType.precision(), parsedType.scale(), span(start, previous())));
            } while (matchSymbol(","));
            expectSymbol(")");
            return parameters;
        }

        private List<Token> consumeRoutineBodyTokens() {
            List<Token> bodyTokens = new ArrayList<>();
            boolean sawBegin = false;
            int blockDepth = 0;
            while (peek().type() != TokenType.EOF) {
                Token token = advance();
                bodyTokens.add(token);
                if (token.type() != TokenType.IDENTIFIER) {
                    continue;
                }
                String keyword = token.text().toUpperCase(Locale.ROOT);
                switch (keyword) {
                    case "BEGIN", "CASE", "IF", "LOOP" -> {
                        sawBegin = true;
                        blockDepth++;
                    }
                    case "END" -> {
                        if (!sawBegin) {
                            throw unsupported("Routine bodies must contain a BEGIN/END block", token.span());
                        }
                        if (blockDepth > 0) {
                            blockDepth--;
                        }
                        if (matchKeyword("IF") || matchKeyword("LOOP") || matchKeyword("CASE")) {
                            bodyTokens.add(previous());
                        }
                        if (peek().type() == TokenType.IDENTIFIER && !peekKeyword("EXCEPTION") && !peekKeyword("WHEN")) {
                            bodyTokens.add(advance());
                        }
                        if (blockDepth == 0) {
                            break;
                        }
                    }
                    default -> {
                    }
                }
                if (sawBegin && blockDepth == 0) {
                    return bodyTokens;
                }
            }
            throw error("Unterminated routine body", peek().span());
        }

        private String normalizeTokens(List<Token> bodyTokens) {
            StringBuilder builder = new StringBuilder();
            Token previousToken = null;
            for (Token token : bodyTokens) {
                if (token.type() == TokenType.EOF) {
                    continue;
                }
                if (previousToken != null && needsSpace(previousToken, token)) {
                    builder.append(' ');
                }
                if (token.type() == TokenType.STRING) {
                    builder.append('\'').append(token.text().replace("'", "''")).append('\'');
                } else {
                    builder.append(token.text());
                }
                previousToken = token;
            }
            return builder.toString().trim();
        }

        private boolean needsSpace(Token previousToken, Token currentToken) {
            if (previousToken.isSymbol(":") && currentToken.isSymbol("=")) {
                return false;
            }
            if (currentToken.isSymbol(",") || currentToken.isSymbol(")") || currentToken.isSymbol(";") || currentToken.isSymbol(".")) {
                return false;
            }
            if (previousToken.isSymbol("(") || previousToken.isSymbol(".") ) {
                return false;
            }
            if (currentToken.type() == TokenType.SYMBOL && Set.of(")", ",", ";", ".").contains(currentToken.text())) {
                return false;
            }
            if (previousToken.type() == TokenType.SYMBOL && Set.of("(", ".").contains(previousToken.text())) {
                return false;
            }
            return true;
        }

        private long parseSignedLong() {
            boolean negative = matchSymbol("-");
            if (!negative) {
                matchSymbol("+");
            }
            Token token = expect(TokenType.NUMBER, "Expected numeric literal");
            long value = Long.parseLong(token.text());
            return negative ? -value : value;
        }

        private int findColumnIndex(List<ColumnDefinition> columns, String columnName, Common.SourceSpan span) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equalsIgnoreCase(columnName)) {
                    return i;
                }
            }
            throw error("Unknown column in table constraint: " + columnName, span);
        }

        private String resolveSingleColumnConstraint(ConstraintSpec constraint) {
            if (constraint.columns().size() != 1) {
                throw unsupported("Composite constraints are not supported yet", constraint.span());
            }
            return constraint.columns().getFirst();
        }

        private String singleReferencedColumn(Expression expression, Common.SourceSpan span) {
            List<String> references = new ArrayList<>();
            collectReferencedColumns(expression, references);
            references = references.stream().distinct().toList();
            if (references.size() != 1) {
                throw unsupported("Table CHECK constraints must reference exactly one column", span);
            }
            return references.getFirst();
        }

        private void collectReferencedColumns(Expression expression, List<String> references) {
            switch (expression) {
                case IdentifierExpression identifier -> {
                    if (identifier.qualifiedName().schema() != null) {
                        throw unsupported("Qualified column references in table CHECK constraints are not supported yet", identifier.span());
                    }
                    references.add(identifier.qualifiedName().name());
                }
                case BinaryExpression binary -> {
                    collectReferencedColumns(binary.left(), references);
                    collectReferencedColumns(binary.right(), references);
                }
                case FunctionCallExpression function -> function.arguments().forEach(argument -> collectReferencedColumns(argument, references));
                case NextValueExpression ignored -> {
                }
                case ParameterExpression ignored -> {
                }
                case LiteralExpression ignored -> {
                }
                case StarExpression ignored -> throw unsupported("'*' is not valid in CHECK constraints", expression.span());
            }
        }

        private Expression mergeCheckExpressions(Expression left, Expression right) {
            return new BinaryExpression(left, BinaryOperator.AND, right, spanToken(left.span(), right.span()));
        }

        private SelectStatement parseSelect(Token start) {
            List<SelectItem> items = parseSelectItems();
            expectKeyword("FROM");
            if (peek().isSymbol("(")) {
                throw unsupported("Subqueries in FROM are not supported yet", peek().span());
            }
            QualifiedName tableName = parseQualifiedName();
            if (peek().type() == TokenType.IDENTIFIER) {
                String next = peek().text().toUpperCase(Locale.ROOT);
                if (JOIN_KEYWORDS.contains(next)) {
                    throw unsupported("JOINs are not supported yet", peek().span());
                }
                if (!QUERY_BOUNDARY_KEYWORDS.contains(next)) {
                    throw unsupported("Table aliases are not supported yet", peek().span());
                }
            }
            Expression where = null;
            if (matchKeyword("WHERE")) {
                where = parseExpression();
            }
            List<Expression> groupBy = new ArrayList<>();
            if (matchKeyword("GROUP")) {
                expectKeyword("BY");
                do {
                    groupBy.add(parseGroupByExpression());
                } while (matchSymbol(","));
            }
            Expression having = null;
            if (matchKeyword("HAVING")) {
                having = parseExpression();
            }
            List<OrderBy> orderBy = new ArrayList<>();
            if (matchKeyword("ORDER")) {
                expectKeyword("BY");
                do {
                    Token orderStart = peek();
                    Expression expression = parseExpression();
                    boolean ascending = !matchKeyword("DESC");
                    if (ascending) {
                        matchKeyword("ASC");
                    }
                    if (matchKeyword("NULLS")) {
                        throw unsupported("NULLS FIRST/LAST is not supported yet", previous().span());
                    }
                    orderBy.add(new OrderBy(expression, ascending, span(orderStart, previous())));
                } while (matchSymbol(","));
            }
            Integer limit = null;
            if (matchKeyword("FETCH")) {
                limit = parseFetchLimit();
            }
            if (matchKeyword("LIMIT")) {
                if (limit != null) {
                    throw error("Cannot combine FETCH and LIMIT in the same SELECT", previous().span());
                }
                limit = Integer.parseInt(expect(TokenType.NUMBER, "Expected numeric LIMIT").text());
            }
            if (peekKeyword("OFFSET")) {
                throw unsupported("OFFSET is not supported yet", peek().span());
            }
            if (peekKeyword("UNION") || peekKeyword("INTERSECT") || peekKeyword("MINUS") || peekKeyword("EXCEPT")) {
                throw unsupported("Set operations are not supported yet", peek().span());
            }
            return new SelectStatement(items, tableName, where, groupBy, having, orderBy, limit, span(start, previous()));
        }

        private Expression parseGroupByExpression() {
            if (peekKeyword("GROUPING") && peekNextKeyword("SETS")) {
                Token start = advance();
                advance();
                expectSymbol("(");
                int depth = 1;
                while (depth > 0) {
                    Token token = advance();
                    if (token.type() == TokenType.EOF) {
                        throw error("Unterminated GROUPING SETS clause", token.span());
                    }
                    if (token.isSymbol("(")) {
                        depth++;
                    } else if (token.isSymbol(")")) {
                        depth--;
                    }
                }
                return new FunctionCallExpression("GROUPING SETS", List.of(), span(start, previous()));
            }
            return parseExpression();
        }

        private Integer parseFetchLimit() {
            if (!(matchKeyword("FIRST") || matchKeyword("NEXT"))) {
                throw error("Expected FIRST or NEXT after FETCH", peek().span());
            }
            Token limitToken = expect(TokenType.NUMBER, "Expected numeric FETCH value");
            if (!(matchKeyword("ROW") || matchKeyword("ROWS"))) {
                throw error("Expected ROW or ROWS after FETCH value", peek().span());
            }
            if (!matchKeyword("ONLY")) {
                throw error("Expected ONLY after FETCH clause", peek().span());
            }
            return Integer.parseInt(limitToken.text());
        }

        private List<SelectItem> parseSelectItems() {
            List<SelectItem> items = new ArrayList<>();
            do {
                Token start = peek();
                Expression expression = parseExpression();
                String alias = null;
                if (matchKeyword("AS")) {
                    alias = parseIdentifier();
                } else if (canStartImplicitAlias()) {
                    alias = parseIdentifier();
                }
                items.add(new SelectItem(expression, alias, span(start, previous())));
            } while (matchSymbol(","));
            return items;
        }

        private boolean canStartImplicitAlias() {
            Token token = peek();
            return token.type() == TokenType.IDENTIFIER && !QUERY_BOUNDARY_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT));
        }

        private List<String> parseIdentifierList() {
            List<String> identifiers = new ArrayList<>();
            do {
                identifiers.add(parseIdentifier());
            } while (matchSymbol(","));
            return identifiers;
        }

        private QualifiedName parseQualifiedName() {
            String first = parseIdentifier();
            if (matchSymbol(".")) {
                return new QualifiedName(first, parseIdentifier());
            }
            return new QualifiedName(null, first);
        }

        private Expression parseExpression() {
            return parseOr();
        }

        private Expression parseOr() {
            Expression expression = parseAnd();
            while (matchKeyword("OR")) {
                Token operator = previous();
                expression = new BinaryExpression(expression, BinaryOperator.OR, parseAnd(), spanToken(expression.span(), operator.span()));
            }
            return expression;
        }

        private Expression parseAnd() {
            Expression expression = parseComparison();
            while (matchKeyword("AND")) {
                Token operator = previous();
                expression = new BinaryExpression(expression, BinaryOperator.AND, parseComparison(), spanToken(expression.span(), operator.span()));
            }
            return expression;
        }

        private Expression parseComparison() {
            Expression expression = parseAdditive();
            while (matchSymbol("=") || matchSymbol("!=") || matchSymbol("<>") || matchSymbol("<") || matchSymbol("<=")
                    || matchSymbol(">") || matchSymbol(">=")) {
                Token operator = previous();
                BinaryOperator binaryOperator = switch (operator.text()) {
                    case "=" -> BinaryOperator.EQ;
                    case "!=", "<>" -> BinaryOperator.NEQ;
                    case "<" -> BinaryOperator.LT;
                    case "<=" -> BinaryOperator.LTE;
                    case ">" -> BinaryOperator.GT;
                    case ">=" -> BinaryOperator.GTE;
                    default -> throw error("Unsupported operator: " + operator.text(), operator.span());
                };
                expression = new BinaryExpression(expression, binaryOperator, parseAdditive(), spanToken(expression.span(), operator.span()));
            }
            return expression;
        }

        private Expression parseAdditive() {
            Expression expression = parseMultiplicative();
            while (matchSymbol("+") || matchSymbol("-")) {
                Token operator = previous();
                expression = new BinaryExpression(expression, operator.text().equals("+") ? BinaryOperator.ADD : BinaryOperator.SUB,
                        parseMultiplicative(), spanToken(expression.span(), operator.span()));
            }
            return expression;
        }

        private Expression parseMultiplicative() {
            Expression expression = parseUnary();
            while (matchSymbol("*") || matchSymbol("/")) {
                Token operator = previous();
                expression = new BinaryExpression(expression, operator.text().equals("*") ? BinaryOperator.MUL : BinaryOperator.DIV,
                        parseUnary(), spanToken(expression.span(), operator.span()));
            }
            return expression;
        }

        private Expression parseUnary() {
            if (matchSymbol("+")) {
                return parseUnary();
            }
            if (matchSymbol("-")) {
                Token operator = previous();
                Expression right = parseUnary();
                LiteralExpression zero = new LiteralExpression(Common.Value.integer(0), operator.span());
                return new BinaryExpression(zero, BinaryOperator.SUB, right, spanToken(operator.span(), right.span()));
            }
            return parsePrimary();
        }

        private Expression parsePrimary() {
            Token token = advance();
            if (token.type() == TokenType.NUMBER) {
                if (token.text().contains(".")) {
                    return new LiteralExpression(Common.Value.decimal(new BigDecimal(token.text())), token.span());
                }
                long value = Long.parseLong(token.text());
                Common.Value literal = value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                        ? Common.Value.integer((int) value)
                        : Common.Value.bigint(value);
                return new LiteralExpression(literal, token.span());
            }
            if (token.type() == TokenType.STRING) {
                return new LiteralExpression(Common.Value.text(token.text()), token.span());
            }
            if (token.type() == TokenType.IDENTIFIER) {
                if ((token.text().equalsIgnoreCase("DATE")
                        || token.text().equalsIgnoreCase("TIME")
                        || token.text().equalsIgnoreCase("TIMESTAMP"))
                        && peek().type() == TokenType.STRING) {
                    Token literal = advance();
                    Common.Value value = switch (token.text().toUpperCase(Locale.ROOT)) {
                        case "DATE" -> Common.Value.date(LocalDate.parse(literal.text()));
                        case "TIME" -> Common.Value.time(LocalTime.parse(literal.text()));
                        case "TIMESTAMP" -> Common.Value.timestamp(Common.Value.parseTimestamp(literal.text()));
                        default -> throw error("Unsupported temporal literal " + token.text(), token.span());
                    };
                    return new LiteralExpression(value, span(token, literal));
                }
                if (token.text().equalsIgnoreCase("NEXT")) {
                    expectKeyword("VALUE");
                    expectKeyword("FOR");
                    QualifiedName sequenceName = parseQualifiedName();
                    return new NextValueExpression(sequenceName, span(token, previous()));
                }
                if (token.text().equalsIgnoreCase("CASE")) {
                    throw unsupported("CASE expressions are not supported yet", token.span());
                }
                if (token.text().equalsIgnoreCase("CAST") || token.text().equalsIgnoreCase("CONVERT")) {
                    throw unsupported(token.text().toUpperCase(Locale.ROOT) + " expressions are not supported yet", token.span());
                }
                if (token.text().equalsIgnoreCase("TRUE") || token.text().equalsIgnoreCase("FALSE")) {
                    return new LiteralExpression(Common.Value.bool(Boolean.parseBoolean(token.text().toLowerCase(Locale.ROOT))), token.span());
                }
                if (token.text().equalsIgnoreCase("NULL")) {
                    return new LiteralExpression(Common.Value.text(null), token.span());
                }
                String qualifier = null;
                String name = token.text();
                Common.SourceSpan nameSpan = token.span();
                if (matchSymbol(".")) {
                    qualifier = token.text();
                    Token member = expect(TokenType.IDENTIFIER, "Expected identifier after .");
                    name = member.text();
                    nameSpan = span(token, member);
                }
                if (matchSymbol("(")) {
                    List<Expression> arguments = new ArrayList<>();
                    if (!matchSymbol(")")) {
                        do {
                            if (matchSymbol("*")) {
                                arguments.add(new StarExpression(previous().span()));
                            } else {
                                arguments.add(parseExpression());
                            }
                        } while (matchSymbol(","));
                        expectSymbol(")");
                    }
                    String functionName = qualifier == null
                            ? name.toUpperCase(Locale.ROOT)
                            : (qualifier + "." + name).toUpperCase(Locale.ROOT);
                    if (peekKeyword("OVER")) {
                        throw unsupported("Analytic/window functions are not supported yet", peek().span());
                    }
                    return new FunctionCallExpression(functionName, arguments, spanToken(nameSpan, previous().span()));
                }
                QualifiedName qualifiedName = qualifier == null
                        ? new QualifiedName(null, name)
                        : new QualifiedName(qualifier, name);
                return new IdentifierExpression(qualifiedName, nameSpan);
            }
            if (token.isSymbol("?")) {
                return new ParameterExpression(++parameterIndex, token.span());
            }
            if (token.isSymbol("(")) {
                if (peekKeyword("SELECT") || peekKeyword("WITH")) {
                    throw unsupported("Subquery expressions are not supported yet", peek().span());
                }
                Expression expression = parseExpression();
                expectSymbol(")");
                return expression;
            }
            if (token.isSymbol("*")) {
                return new StarExpression(token.span());
            }
            throw error("Expected expression but found " + token.text(), token.span());
        }

        private List<Token> collectTokensUntil(Set<String> stopDelimiters, Set<String> stopKeywords, boolean trackParens) {
            List<Token> collected = new ArrayList<>();
            int depth = 0;
            while (peek().type() != TokenType.EOF) {
                Token token = peek();
                int previousDepth = depth;
                if (trackParens && token.isSymbol("(")) {
                    depth++;
                } else if (trackParens && token.isSymbol(")")) {
                    if (depth == 0 && stopDelimiters.contains(")")) {
                        break;
                    }
                    depth = Math.max(depth - 1, 0);
                }
                if (depth == 0) {
                    if (token.type() == TokenType.SYMBOL
                            && stopDelimiters.contains(token.text())
                            && !(trackParens && token.isSymbol(")") && previousDepth > 0)) {
                        break;
                    }
                    if (token.type() == TokenType.IDENTIFIER
                            && stopKeywords.contains(token.text().toUpperCase(Locale.ROOT))) {
                        break;
                    }
                }
                collected.add(advance());
            }
            return collected;
        }

        private boolean isConstraintStart() {
            return peekKeyword("CONSTRAINT")
                    || peekKeyword("PRIMARY")
                    || peekKeyword("UNIQUE")
                    || peekKeyword("CHECK")
                    || peekKeyword("FOREIGN");
        }

        private boolean isInlineConstraintStart() {
            return isConstraintStart()
                    || peekKeyword("NOT")
                    || peekKeyword("NULL")
                    || peekKeyword("REFERENCES");
        }

        private boolean peekKeyword(String keyword) {
            return peek().isKeyword(keyword);
        }

        private boolean peekNextKeyword(String keyword) {
            return index + 1 < tokens.size() && tokens.get(index + 1).isKeyword(keyword);
        }

        private boolean matchKeyword(String keyword) {
            if (peek().isKeyword(keyword)) {
                index++;
                return true;
            }
            return false;
        }

        private boolean matchSymbol(String symbol) {
            if (peek().isSymbol(symbol)) {
                index++;
                return true;
            }
            return false;
        }

        private void expectKeyword(String keyword) {
            if (!matchKeyword(keyword)) {
                throw error("Expected keyword " + keyword + " but found " + peek().text(), peek().span());
            }
        }

        private void expectSymbol(String symbol) {
            if (!matchSymbol(symbol)) {
                throw error("Expected symbol " + symbol + " but found " + peek().text(), peek().span());
            }
        }

        private Token expect(TokenType type, String message) {
            Token token = advance();
            if (token.type() != type) {
                throw error(message, token.span());
            }
            return token;
        }

        private String parseIdentifier() {
            Token token = advance();
            if (token.type() != TokenType.IDENTIFIER) {
                throw error("Expected identifier but found " + token.text(), token.span());
            }
            return token.text().toLowerCase(Locale.ROOT);
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token previous() {
            return tokens.get(Math.max(0, index - 1));
        }

        private Token advance() {
            return tokens.get(index++);
        }

        private Common.DatabaseException error(String message, Common.SourceSpan span) {
            return new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR, message, span);
        }

        private Common.DatabaseException unsupported(String message, Common.SourceSpan span) {
            return new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, message, span);
        }

        private Common.SourceSpan span(Token start, Token end) {
            return new Common.SourceSpan(start.span().line(), start.span().column(), end.span().endLine(), end.span().endColumn());
        }

        private Common.SourceSpan spanToken(Common.SourceSpan start, Common.SourceSpan end) {
            return new Common.SourceSpan(start.line(), start.column(), end.endLine(), end.endColumn());
        }
    }

    private static final class Lexer {
        private final String input;
        private final List<Token> tokens = new ArrayList<>();
        private int index;
        private int line = 1;
        private int column = 1;

        private Lexer(String input) {
            this.input = input;
        }

        private List<Token> lex() {
            while (!eof()) {
                char ch = peek();
                if (Character.isWhitespace(ch)) {
                    advanceChar();
                    continue;
                }
                if (ch == '-' && peekNext() == '-') {
                    skipLineComment();
                    continue;
                }
                if (ch == '/' && peekNext() == '*') {
                    skipBlockComment();
                    continue;
                }
                if (Character.isLetter(ch) || ch == '_') {
                    lexIdentifier();
                    continue;
                }
                if (Character.isDigit(ch)) {
                    lexNumber();
                    continue;
                }
                if (ch == '\'') {
                    lexString();
                    continue;
                }
                lexSymbol();
            }
            tokens.add(new Token(TokenType.EOF, "<eof>", new Common.SourceSpan(line, column, line, column)));
            return tokens;
        }

        private void lexIdentifier() {
            int startLine = line;
            int startColumn = column;
            StringBuilder builder = new StringBuilder();
            while (!eof() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
                builder.append(advanceChar());
            }
            tokens.add(new Token(TokenType.IDENTIFIER, builder.toString(), new Common.SourceSpan(startLine, startColumn, line, column)));
        }

        private void lexNumber() {
            int startLine = line;
            int startColumn = column;
            StringBuilder builder = new StringBuilder();
            while (!eof() && Character.isDigit(peek())) {
                builder.append(advanceChar());
            }
            if (!eof() && peek() == '.' && index + 1 < input.length() && Character.isDigit(input.charAt(index + 1))) {
                builder.append(advanceChar());
                while (!eof() && Character.isDigit(peek())) {
                    builder.append(advanceChar());
                }
            }
            tokens.add(new Token(TokenType.NUMBER, builder.toString(), new Common.SourceSpan(startLine, startColumn, line, column)));
        }

        private void lexString() {
            int startLine = line;
            int startColumn = column;
            advanceChar();
            StringBuilder builder = new StringBuilder();
            while (!eof()) {
                char ch = advanceChar();
                if (ch == '\'') {
                    if (!eof() && peek() == '\'') {
                        advanceChar();
                        builder.append('\'');
                        continue;
                    }
                    tokens.add(new Token(TokenType.STRING, builder.toString(), new Common.SourceSpan(startLine, startColumn, line, column)));
                    return;
                }
                builder.append(ch);
            }
            throw new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR, "Unterminated string literal",
                    new Common.SourceSpan(startLine, startColumn, line, column));
        }

        private void lexSymbol() {
            int startLine = line;
            int startColumn = column;
            char ch = advanceChar();
            String text = String.valueOf(ch);
            if (!eof()) {
                char next = peek();
                if ((ch == '!' || ch == '<' || ch == '>') && next == '=') {
                    text = "" + ch + advanceChar();
                } else if (ch == '<' && next == '>') {
                    text = "" + ch + advanceChar();
                }
            }
            tokens.add(new Token(TokenType.SYMBOL, text, new Common.SourceSpan(startLine, startColumn, line, column)));
        }

        private void skipLineComment() {
            advanceChar();
            advanceChar();
            while (!eof() && peek() != '\n') {
                advanceChar();
            }
        }

        private void skipBlockComment() {
            int startLine = line;
            int startColumn = column;
            advanceChar();
            advanceChar();
            while (!eof()) {
                char ch = advanceChar();
                if (ch == '*' && !eof() && peek() == '/') {
                    advanceChar();
                    return;
                }
            }
            throw new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR, "Unterminated block comment",
                    new Common.SourceSpan(startLine, startColumn, line, column));
        }

        private boolean eof() {
            return index >= input.length();
        }

        private char peek() {
            return input.charAt(index);
        }

        private char peekNext() {
            return index + 1 >= input.length() ? '\0' : input.charAt(index + 1);
        }

        private char advanceChar() {
            char ch = input.charAt(index++);
            if (ch == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            return ch;
        }
    }
}
