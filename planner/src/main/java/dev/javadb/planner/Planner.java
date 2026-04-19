package dev.javadb.planner;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.index.Indexes;
import dev.javadb.sql.SqlFrontend;
import dev.javadb.storage.Storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

public final class Planner {
    private Planner() {
    }

    public sealed interface BoundStatement permits BoundCatalogStatement, BoundInsert, BoundSelect, BoundUpdate, BoundDelete,
            ReferenceQueries.BoundReferenceQuery {
        Common.SourceSpan span();
    }

    public sealed interface BoundExpression permits BoundColumnRef, BoundLiteral, BoundBinary, BoundAggregate,
            BoundScalarFunction, BoundNextValue {
        Common.DataType type();
        Common.SourceSpan span();
    }

    public record BoundColumnRef(int ordinal, String name, Common.DataType type, Common.SourceSpan span) implements BoundExpression {
    }

    public record BoundLiteral(Common.Value value, Common.SourceSpan span) implements BoundExpression {
        @Override
        public Common.DataType type() {
            return value.type();
        }
    }

    public record BoundBinary(BoundExpression left, SqlFrontend.BinaryOperator operator, BoundExpression right,
                              Common.DataType type, Common.SourceSpan span) implements BoundExpression {
    }

    public enum AggregateFunction {
        COUNT,
        SUM,
        MIN,
        MAX,
        AVG
    }

    public record BoundAggregate(AggregateFunction function, BoundExpression argument, Common.DataType type,
                                 Common.SourceSpan span) implements BoundExpression {
    }

    public enum ScalarFunction {
        LOWER,
        UPPER,
        LENGTH,
        ABS,
        COALESCE,
        TRIM,
        SUBSTR,
        REPLACE
    }

    public record BoundScalarFunction(ScalarFunction function, List<BoundExpression> arguments, Common.DataType type,
                                      Common.SourceSpan span) implements BoundExpression {
        public BoundScalarFunction {
            arguments = List.copyOf(arguments);
        }
    }

    public record BoundNextValue(Catalog.SequenceDefinition sequence, Common.SourceSpan span) implements BoundExpression {
        @Override
        public Common.DataType type() {
            return Common.DataType.BIGINT;
        }
    }

    public record BoundSelectItem(String outputName, BoundExpression expression) {
    }

    public record BoundOrderBy(BoundExpression expression, boolean ascending) {
    }

    public record BoundAssignment(int ordinal, String column, BoundExpression expression) {
    }

    public record BoundCatalogStatement(Catalog.CatalogChange change, Common.SourceSpan span) implements BoundStatement {
    }

    public record BoundInsert(Catalog.TableDefinition table, List<Integer> targetOrdinals,
                              List<List<BoundExpression>> rows, Common.SourceSpan span) implements BoundStatement {
    }

    public record BoundSelect(Catalog.TableDefinition table, List<BoundSelectItem> items,
                              BoundExpression filter, List<BoundExpression> groupBy, BoundExpression having,
                              List<BoundOrderBy> orderBy, Integer limit, boolean aggregateQuery,
                              Common.SourceSpan span) implements BoundStatement {
    }

    public record BoundUpdate(Catalog.TableDefinition table, List<BoundAssignment> assignments,
                              BoundExpression filter, Common.SourceSpan span) implements BoundStatement {
    }

    public record BoundDelete(Catalog.TableDefinition table, BoundExpression filter, Common.SourceSpan span) implements BoundStatement {
    }

    public record PreparedParameterDescription(int index, Common.DataType type,
                                               Integer precision, Integer scale, boolean nullable) {
    }

    public sealed interface PhysicalPlan permits CatalogPlan, InsertPlan, TableScanPlan, IndexLookupPlan, EmptyResultPlan, UpdatePlan, DeletePlan,
            ReferenceQueries.ReferenceQueryPlan {
        String explain();
    }

    public record CatalogPlan(BoundCatalogStatement statement) implements PhysicalPlan {
        @Override
        public String explain() {
            return "CatalogChange(" + statement.change().getClass().getSimpleName() + ")";
        }
    }

    public record InsertPlan(BoundInsert statement) implements PhysicalPlan {
        @Override
        public String explain() {
            return "Insert(table=" + statement.table().name().toSql() + ")";
        }
    }

    public record TableScanPlan(BoundSelect statement) implements PhysicalPlan {
        @Override
        public String explain() {
            return "TableScan(table=" + statement.table().name().toSql()
                    + ", aggregate=" + statement.aggregateQuery()
                    + ", groupBy=" + statement.groupBy().size()
                    + ", orderBy=" + statement.orderBy().size()
                    + (statement.limit() == null ? "" : ", limit=" + statement.limit())
                    + ")";
        }
    }

    public record IndexLookupPlan(BoundSelect statement, Catalog.IndexDefinition index, Common.Value keyValue) implements PhysicalPlan {
        @Override
        public String explain() {
            return "IndexLookup(table=" + statement.table().name().toSql()
                    + ", index=" + index.name()
                    + ", aggregate=" + statement.aggregateQuery()
                    + ", groupBy=" + statement.groupBy().size()
                    + ", orderBy=" + statement.orderBy().size()
                    + (statement.limit() == null ? "" : ", limit=" + statement.limit())
                    + ")";
        }
    }

    public record EmptyResultPlan(BoundSelect statement) implements PhysicalPlan {
        @Override
        public String explain() {
            return "EmptyResult(table=" + statement.table().name().toSql()
                    + ", aggregate=" + statement.aggregateQuery()
                    + ", groupBy=" + statement.groupBy().size()
                    + ", orderBy=" + statement.orderBy().size()
                    + (statement.limit() == null ? "" : ", limit=" + statement.limit())
                    + ")";
        }
    }

    public record UpdatePlan(BoundUpdate statement) implements PhysicalPlan {
        @Override
        public String explain() {
            return "Update(table=" + statement.table().name().toSql() + ")";
        }
    }

    public record DeletePlan(BoundDelete statement) implements PhysicalPlan {
        @Override
        public String explain() {
            return "Delete(table=" + statement.table().name().toSql() + ")";
        }
    }

    public static final class Binder {
        private final Catalog.CatalogSnapshot catalogSnapshot;
        private final LongSupplier nextObjectId;
        private final Map<Integer, PreparedParameterDescription> parameterDescriptions = new LinkedHashMap<>();

        private record TypeHint(Common.DataType type, Integer precision, Integer scale) {
            static final TypeHint UNKNOWN = new TypeHint(null, null, null);

            static TypeHint of(Common.DataType type) {
                return new TypeHint(type, null, null);
            }
        }

        public Binder(Catalog.CatalogSnapshot catalogSnapshot, LongSupplier nextObjectId) {
            this.catalogSnapshot = Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
            this.nextObjectId = Objects.requireNonNull(nextObjectId, "nextObjectId");
        }

        public List<PreparedParameterDescription> parameterDescriptions() {
            return parameterDescriptions.values().stream()
                    .sorted(java.util.Comparator.comparingInt(PreparedParameterDescription::index))
                    .toList();
        }

        public BoundStatement bind(SqlFrontend.Statement statement) {
            return switch (statement) {
                case SqlFrontend.CreateSchemaStatement createSchema -> new BoundCatalogStatement(
                        new Catalog.CreateSchemaChange(new Common.ObjectId(nextObjectId.getAsLong()), createSchema.schemaName().toLowerCase(Locale.ROOT)),
                        createSchema.span());
                case SqlFrontend.CreateTableStatement createTable -> bindCreateTable(createTable);
                case SqlFrontend.CreateIndexStatement createIndex -> bindCreateIndex(createIndex);
                case SqlFrontend.CreateSequenceStatement createSequence -> bindCreateSequence(createSequence);
                case SqlFrontend.CreateRoutineStatement createRoutine -> bindCreateRoutine(createRoutine);
                case SqlFrontend.InsertStatement insert -> bindInsert(insert);
                case SqlFrontend.SelectStatement select -> bindSelect(select);
                case SqlFrontend.UpdateStatement update -> bindUpdate(update);
                case SqlFrontend.DeleteStatement delete -> bindDelete(delete);
                case SqlFrontend.ReferenceStatement referenceStatement -> ReferenceQueries.bind(catalogSnapshot, referenceStatement);
                default -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Unsupported bind request for statement type " + statement.getClass().getSimpleName(), statement.span());
            };
        }

        private BoundCatalogStatement bindCreateTable(SqlFrontend.CreateTableStatement statement) {
            List<Catalog.ColumnDefinition> columns = new ArrayList<>();
            int ordinal = 0;
            for (SqlFrontend.ColumnDefinition column : statement.columns()) {
                Common.DataType columnType = Common.DataType.fromSql(column.typeName());
                Integer precision = normalizePrecision(columnType, column.typePrecision(), column.typeScale(), column.span());
                Integer scale = normalizeScale(columnType, column.typePrecision(), column.typeScale(), column.span());
                Catalog.IdentityDefinition identityDefinition = null;
                if (column.identityDefinition() != null) {
                    if (!(columnType == Common.DataType.INTEGER || columnType == Common.DataType.BIGINT)) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                "Identity columns must use INTEGER or BIGINT types", column.identityDefinition().span());
                    }
                    identityDefinition = new Catalog.IdentityDefinition(
                            switch (column.identityDefinition().generation()) {
                                case ALWAYS -> Catalog.IdentityGeneration.ALWAYS;
                                case BY_DEFAULT -> Catalog.IdentityGeneration.BY_DEFAULT;
                            },
                            toCatalogSequenceOptions(column.identityDefinition().options()));
                }
                columns.add(new Catalog.ColumnDefinition(ordinal++, column.name().toLowerCase(Locale.ROOT),
                        columnType, !column.notNull(), column.primaryKey(), column.unique(),
                        column.checkExpression() == null ? null : SqlFrontend.renderExpression(column.checkExpression()),
                        identityDefinition, precision, scale));
            }
            return new BoundCatalogStatement(new Catalog.CreateTableChange(new Common.ObjectId(nextObjectId.getAsLong()),
                    Catalog.QualifiedName.from(statement.tableName()), columns), statement.span());
        }

        private BoundCatalogStatement bindCreateIndex(SqlFrontend.CreateIndexStatement statement) {
            Catalog.TableDefinition table = catalogSnapshot.requireTable(Catalog.QualifiedName.from(statement.tableName()));
            return new BoundCatalogStatement(new Catalog.CreateIndexChange(new Common.ObjectId(nextObjectId.getAsLong()),
                    statement.indexName().toLowerCase(Locale.ROOT), table.id(), statement.columns(), statement.unique()), statement.span());
        }

        private BoundCatalogStatement bindCreateSequence(SqlFrontend.CreateSequenceStatement statement) {
            return new BoundCatalogStatement(new Catalog.CreateSequenceChange(
                    new Common.ObjectId(nextObjectId.getAsLong()),
                    Catalog.QualifiedName.from(statement.sequenceName()),
                    toCatalogSequenceOptions(statement.options())), statement.span());
        }

        private BoundCatalogStatement bindCreateRoutine(SqlFrontend.CreateRoutineStatement statement) {
            List<Catalog.RoutineParameter> parameters = new ArrayList<>();
            int ordinal = 0;
            for (SqlFrontend.RoutineParameter parameter : statement.parameters()) {
                Common.DataType parameterType = Common.DataType.fromSql(parameter.typeName());
                parameters.add(new Catalog.RoutineParameter(ordinal++, parameter.name().toLowerCase(Locale.ROOT),
                        parameterType,
                        switch (parameter.mode()) {
                            case IN -> Catalog.ParameterMode.IN;
                            case OUT -> Catalog.ParameterMode.OUT;
                            case INOUT -> Catalog.ParameterMode.INOUT;
                        },
                        normalizePrecision(parameterType, parameter.typePrecision(), parameter.typeScale(), parameter.span()),
                        normalizeScale(parameterType, parameter.typePrecision(), parameter.typeScale(), parameter.span())));
            }
            Common.DataType returnType = statement.returnTypeName() == null ? null : Common.DataType.fromSql(statement.returnTypeName());
            return new BoundCatalogStatement(new Catalog.CreateRoutineChange(
                    new Common.ObjectId(nextObjectId.getAsLong()),
                    Catalog.QualifiedName.from(statement.routineName()),
                    switch (statement.kind()) {
                        case FUNCTION -> Catalog.RoutineKind.FUNCTION;
                        case PROCEDURE -> Catalog.RoutineKind.PROCEDURE;
                    },
                    parameters,
                    returnType,
                    normalizePrecision(returnType, statement.returnTypePrecision(), statement.returnTypeScale(), statement.span()),
                    normalizeScale(returnType, statement.returnTypePrecision(), statement.returnTypeScale(), statement.span()),
                    statement.bodySql()), statement.span());
        }

        private Integer normalizePrecision(Common.DataType type, Integer precision, Integer scale, Common.SourceSpan span) {
            if (type == null || type != Common.DataType.DECIMAL) {
                return null;
            }
            if (precision == null && scale == null) {
                return null;
            }
            int effectivePrecision = precision == null ? type.defaultPrecision() : precision;
            int effectiveScale = scale == null ? 0 : scale;
            if (effectivePrecision <= 0) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "DECIMAL precision must be positive", span);
            }
            if (effectiveScale < 0 || effectiveScale > effectivePrecision) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "DECIMAL scale must be between 0 and precision", span);
            }
            return effectivePrecision;
        }

        private Integer normalizeScale(Common.DataType type, Integer precision, Integer scale, Common.SourceSpan span) {
            if (type == null || type != Common.DataType.DECIMAL) {
                return null;
            }
            if (precision == null && scale == null) {
                return null;
            }
            normalizePrecision(type, precision, scale, span);
            return scale == null ? 0 : scale;
        }

        private Catalog.SequenceOptions toCatalogSequenceOptions(SqlFrontend.SequenceOptions options) {
            return new Catalog.SequenceOptions(
                    options == null ? null : options.startWith(),
                    options == null ? null : options.incrementBy(),
                    options == null ? null : options.minValue(),
                    options == null ? null : options.maxValue(),
                    options == null ? null : options.cacheSize(),
                    options != null && options.cycle());
        }

        private BoundInsert bindInsert(SqlFrontend.InsertStatement statement) {
            Catalog.TableDefinition table = catalogSnapshot.requireTable(Catalog.QualifiedName.from(statement.tableName()));
            List<Integer> targetOrdinals = statement.columns().isEmpty()
                    ? table.columns().stream().map(Catalog.ColumnDefinition::ordinal).toList()
                    : statement.columns().stream().map(column -> table.requireColumn(column).ordinal()).toList();
            List<List<BoundExpression>> rows = new ArrayList<>();
            for (List<SqlFrontend.Expression> row : statement.rows()) {
                if (row.size() != targetOrdinals.size()) {
                    throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "INSERT column count does not match values count", statement.span());
                }
                List<BoundExpression> boundRow = new ArrayList<>();
                for (int index = 0; index < row.size(); index++) {
                    Catalog.ColumnDefinition column = table.columns().get(targetOrdinals.get(index));
                    boundRow.add(bindExpression(row.get(index), table,
                            new TypeHint(column.type(), column.precision(), column.scale())));
                }
                rows.add(boundRow);
            }
            return new BoundInsert(table, targetOrdinals, rows, statement.span());
        }

        private BoundSelect bindSelect(SqlFrontend.SelectStatement statement) {
            Catalog.TableDefinition table = catalogSnapshot.requireTable(Catalog.QualifiedName.from(statement.from()));
            BoundExpression filter = statement.where() == null ? null : bindScalarExpression(statement.where(), table, Common.DataType.BOOLEAN, "WHERE");
            List<BoundExpression> groupBy = statement.groupBy().stream()
                    .map(expression -> bindScalarExpression(expression, table, null, "GROUP BY"))
                    .toList();

            List<BoundSelectItem> items = new ArrayList<>();
            boolean aggregateQuery = !groupBy.isEmpty();
            for (SqlFrontend.SelectItem item : statement.selectItems()) {
                if (item.expression() instanceof SqlFrontend.StarExpression) {
                    for (Catalog.ColumnDefinition column : table.columns()) {
                        items.add(new BoundSelectItem(column.name(), new BoundColumnRef(column.ordinal(), column.name(), column.type(), item.span())));
                    }
                    continue;
                }
                BoundExpression expression = bindExpression(item.expression(), table, TypeHint.UNKNOWN);
                aggregateQuery |= containsAggregate(expression);
                String outputName = item.alias() != null ? item.alias().toLowerCase(Locale.ROOT)
                        : item.expression() instanceof SqlFrontend.IdentifierExpression identifier
                        ? identifier.qualifiedName().name()
                        : SqlFrontend.renderExpression(item.expression()).toLowerCase(Locale.ROOT);
                items.add(new BoundSelectItem(outputName, expression));
            }
            BoundExpression having = statement.having() == null ? null : bindExpression(statement.having(), table, TypeHint.of(Common.DataType.BOOLEAN));
            aggregateQuery |= containsAggregate(having);
            if (having != null && !groupBy.isEmpty()) {
                aggregateQuery = true;
            }
            List<BoundOrderBy> orderBy = new ArrayList<>();
            for (SqlFrontend.OrderBy order : statement.orderBy()) {
                BoundExpression expression = bindExpression(order.expression(), table, TypeHint.UNKNOWN);
                aggregateQuery |= containsAggregate(expression);
                orderBy.add(new BoundOrderBy(expression, order.ascending()));
            }
            if (statement.having() != null && !aggregateQuery) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "HAVING requires GROUP BY or an aggregate expression", statement.having().span());
            }
            if (aggregateQuery) {
                validateAggregateProjection(items, groupBy);
                if (having != null) {
                    validateAggregateExpression(having, groupBy, "HAVING");
                }
                for (BoundOrderBy order : orderBy) {
                    validateAggregateExpression(order.expression(), groupBy, "ORDER BY");
                }
            }
            return new BoundSelect(table, items, filter, groupBy, having, orderBy, statement.limit(), aggregateQuery, statement.span());
        }

        private BoundUpdate bindUpdate(SqlFrontend.UpdateStatement statement) {
            Catalog.TableDefinition table = catalogSnapshot.requireTable(Catalog.QualifiedName.from(statement.tableName()));
            List<BoundAssignment> assignments = new ArrayList<>();
            for (SqlFrontend.Assignment assignment : statement.assignments()) {
                Catalog.ColumnDefinition column = table.requireColumn(assignment.column());
                assignments.add(new BoundAssignment(column.ordinal(), column.name(), bindExpression(assignment.expression(), table,
                        new TypeHint(column.type(), column.precision(), column.scale()))));
            }
            BoundExpression filter = statement.where() == null ? null : bindExpression(statement.where(), table, TypeHint.of(Common.DataType.BOOLEAN));
            return new BoundUpdate(table, assignments, filter, statement.span());
        }

        private BoundDelete bindDelete(SqlFrontend.DeleteStatement statement) {
            Catalog.TableDefinition table = catalogSnapshot.requireTable(Catalog.QualifiedName.from(statement.tableName()));
            BoundExpression filter = statement.where() == null ? null : bindExpression(statement.where(), table, TypeHint.of(Common.DataType.BOOLEAN));
            return new BoundDelete(table, filter, statement.span());
        }

        private BoundExpression bindScalarExpression(SqlFrontend.Expression expression, Catalog.TableDefinition table,
                                                     Common.DataType expectedType, String context) {
            BoundExpression bound = bindExpression(expression, table, TypeHint.of(expectedType));
            if (containsAggregate(bound)) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        context + " cannot contain aggregate functions", expression.span());
            }
            return bound;
        }

        private BoundExpression bindExpression(SqlFrontend.Expression expression, Catalog.TableDefinition table, TypeHint expectedType) {
            BoundExpression bound = switch (expression) {
                case SqlFrontend.IdentifierExpression identifier -> {
                    String name = identifier.qualifiedName().name().toLowerCase(Locale.ROOT);
                    Catalog.ColumnDefinition column = table.requireColumn(name);
                    yield new BoundColumnRef(column.ordinal(), column.name(), column.type(), identifier.span());
                }
                case SqlFrontend.LiteralExpression literal -> expectedType == null || expectedType.type() == null
                        ? new BoundLiteral(literal.value(), literal.span())
                        : new BoundLiteral(Common.Values.coerce(literal.value(), expectedType.type(),
                        expectedType.precision(), expectedType.scale()), literal.span());
                case SqlFrontend.ParameterExpression parameter -> bindParameter(parameter, expectedType);
                case SqlFrontend.BinaryExpression binary -> bindBinary(binary, table);
                case SqlFrontend.FunctionCallExpression functionCall -> bindFunction(functionCall, table);
                case SqlFrontend.NextValueExpression nextValue -> catalogSnapshot.sequence(Catalog.QualifiedName.from(nextValue.sequenceName()))
                        .<BoundExpression>map(sequence -> new BoundNextValue(sequence, nextValue.span()))
                        .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                "Unknown sequence " + Catalog.QualifiedName.from(nextValue.sequenceName()).toSql(), nextValue.span()));
                case SqlFrontend.StarExpression star -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Standalone * is only valid in SELECT lists", star.span());
            };
            if (expectedType != null && expectedType.type() != null
                    && bound.type() != expectedType.type() && !(bound instanceof BoundAggregate)) {
                if (bound instanceof BoundLiteral literal) {
                    return new BoundLiteral(Common.Values.coerce(literal.value(), expectedType.type(),
                            expectedType.precision(), expectedType.scale()), bound.span());
                }
            }
            return bound;
        }

        private BoundExpression bindParameter(SqlFrontend.ParameterExpression parameter, TypeHint hint) {
            Common.DataType type = hint == null || hint.type() == null ? Common.DataType.TEXT : hint.type();
            Integer precision = hint == null ? null : hint.precision();
            Integer scale = hint == null ? null : hint.scale();
            parameterDescriptions.put(parameter.index(),
                    new PreparedParameterDescription(parameter.index(), type, precision, scale, true));
            return new BoundLiteral(Common.Value.nullValue(type), parameter.span());
        }

        private BoundExpression bindBinary(SqlFrontend.BinaryExpression binary, Catalog.TableDefinition table) {
            BoundExpression left;
            BoundExpression right;
            if (binary.operator() == SqlFrontend.BinaryOperator.AND || binary.operator() == SqlFrontend.BinaryOperator.OR) {
                left = bindExpression(binary.left(), table, TypeHint.of(Common.DataType.BOOLEAN));
                right = bindExpression(binary.right(), table, TypeHint.of(Common.DataType.BOOLEAN));
            } else if (binary.left() instanceof SqlFrontend.ParameterExpression
                    && !(binary.right() instanceof SqlFrontend.ParameterExpression)) {
                right = bindExpression(binary.right(), table, TypeHint.UNKNOWN);
                left = bindExpression(binary.left(), table, parameterHint(binary.operator(), right, table));
            } else if (binary.right() instanceof SqlFrontend.ParameterExpression
                    && !(binary.left() instanceof SqlFrontend.ParameterExpression)) {
                left = bindExpression(binary.left(), table, TypeHint.UNKNOWN);
                right = bindExpression(binary.right(), table, parameterHint(binary.operator(), left, table));
            } else {
                TypeHint sharedHint = switch (binary.operator()) {
                    case ADD, SUB, MUL, DIV -> TypeHint.of(Common.DataType.DECIMAL);
                    case AND, OR -> TypeHint.of(Common.DataType.BOOLEAN);
                    default -> TypeHint.UNKNOWN;
                };
                left = bindExpression(binary.left(), table, sharedHint);
                right = bindExpression(binary.right(), table, sharedHint);
            }
            Common.DataType type = switch (binary.operator()) {
                case EQ, NEQ, LT, LTE, GT, GTE -> Common.DataType.BOOLEAN;
                case AND, OR -> {
                    requireType(binary.operator().sql(), left, Common.DataType.BOOLEAN, binary.span());
                    requireType(binary.operator().sql(), right, Common.DataType.BOOLEAN, binary.span());
                    yield Common.DataType.BOOLEAN;
                }
                case ADD, SUB, MUL, DIV -> resolveNumericBinaryType(left, right, binary.span());
            };
            BoundExpression simplified = simplifyBooleanBinary(left, binary.operator(), right, binary.span());
            if (simplified != null) {
                return simplified;
            }
            if (left instanceof BoundLiteral leftLiteral && right instanceof BoundLiteral rightLiteral) {
                return new BoundLiteral(evaluateBinaryLiteral(leftLiteral.value(), binary.operator(), rightLiteral.value(), type), binary.span());
            }
            return new BoundBinary(left, binary.operator(), right, type, binary.span());
        }

        private BoundExpression bindFunction(SqlFrontend.FunctionCallExpression functionCall, Catalog.TableDefinition table) {
            String name = functionCall.name().toUpperCase(Locale.ROOT);
            if (name.equals("COUNT")) {
                if (functionCall.arguments().size() != 1) {
                    throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "COUNT expects exactly one argument", functionCall.span());
                }
                if (functionCall.arguments().getFirst() instanceof SqlFrontend.StarExpression) {
                    return new BoundAggregate(AggregateFunction.COUNT, null, Common.DataType.BIGINT, functionCall.span());
                }
                BoundExpression argument = bindAggregateArgument(functionCall, table, TypeHint.UNKNOWN);
                return new BoundAggregate(AggregateFunction.COUNT, argument, Common.DataType.BIGINT, functionCall.span());
            }
            if (name.equals("SUM")) {
                BoundExpression argument = bindAggregateArgument(functionCall, table, TypeHint.of(Common.DataType.DECIMAL));
                if (!argument.type().numeric()) {
                    throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "SUM requires a numeric argument", functionCall.span());
                }
                return new BoundAggregate(AggregateFunction.SUM, argument, argument.type(), functionCall.span());
            }
            if (name.equals("MIN")) {
                BoundExpression argument = bindAggregateArgument(functionCall, table, TypeHint.UNKNOWN);
                return new BoundAggregate(AggregateFunction.MIN, argument, argument.type(), functionCall.span());
            }
            if (name.equals("MAX")) {
                BoundExpression argument = bindAggregateArgument(functionCall, table, TypeHint.UNKNOWN);
                return new BoundAggregate(AggregateFunction.MAX, argument, argument.type(), functionCall.span());
            }
            if (name.equals("AVG")) {
                BoundExpression argument = bindAggregateArgument(functionCall, table, TypeHint.of(Common.DataType.DECIMAL));
                if (!argument.type().numeric()) {
                    throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "AVG requires a numeric argument", functionCall.span());
                }
                return new BoundAggregate(AggregateFunction.AVG, argument,
                        argument.type() == Common.DataType.DECIMAL ? Common.DataType.DECIMAL : Common.DataType.BIGINT,
                        functionCall.span());
            }
            return bindScalarFunction(functionCall, table);
        }

        private BoundExpression bindAggregateArgument(SqlFrontend.FunctionCallExpression functionCall, Catalog.TableDefinition table,
                                                     TypeHint expectedHint) {
            if (functionCall.arguments().size() != 1 || functionCall.arguments().getFirst() instanceof SqlFrontend.StarExpression) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        functionCall.name() + " expects exactly one non-star argument", functionCall.span());
            }
            BoundExpression argument = bindExpression(functionCall.arguments().getFirst(), table, expectedHint);
            if (containsAggregate(argument)) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Nested aggregate functions are not supported", functionCall.span());
            }
            return argument;
        }

        private BoundExpression bindScalarFunction(SqlFrontend.FunctionCallExpression functionCall, Catalog.TableDefinition table) {
            String name = functionCall.name().toUpperCase(Locale.ROOT);
            List<BoundExpression> arguments = switch (name) {
                case "LOWER", "UPPER", "LENGTH", "TRIM" -> bindArguments(functionCall.arguments(), table,
                        List.of(TypeHint.of(Common.DataType.TEXT)));
                case "ABS" -> bindArguments(functionCall.arguments(), table,
                        List.of(TypeHint.of(Common.DataType.DECIMAL)));
                case "SUBSTR", "SUBSTRING" -> bindArguments(functionCall.arguments(), table,
                        List.of(TypeHint.of(Common.DataType.TEXT), TypeHint.of(Common.DataType.BIGINT), TypeHint.of(Common.DataType.BIGINT)));
                case "REPLACE" -> bindArguments(functionCall.arguments(), table,
                        List.of(TypeHint.of(Common.DataType.TEXT), TypeHint.of(Common.DataType.TEXT), TypeHint.of(Common.DataType.TEXT)));
                case "COALESCE", "NVL" -> bindCoalesceArguments(functionCall.arguments(), table);
                default -> functionCall.arguments().stream()
                        .map(argument -> bindExpression(argument, table, TypeHint.UNKNOWN))
                        .toList();
            };
            ScalarFunction function = switch (name) {
                case "LOWER" -> ScalarFunction.LOWER;
                case "UPPER" -> ScalarFunction.UPPER;
                case "LENGTH" -> ScalarFunction.LENGTH;
                case "ABS" -> ScalarFunction.ABS;
                case "COALESCE", "NVL" -> ScalarFunction.COALESCE;
                case "TRIM" -> ScalarFunction.TRIM;
                case "SUBSTR", "SUBSTRING" -> ScalarFunction.SUBSTR;
                case "REPLACE" -> ScalarFunction.REPLACE;
                default -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Only COUNT, SUM, MIN, MAX, AVG, LOWER, UPPER, LENGTH, ABS, COALESCE, NVL, TRIM, SUBSTR, SUBSTRING, and REPLACE are supported in the current execution slice",
                        functionCall.span());
            };
            Common.DataType resultType = resolveScalarFunctionType(function, name, arguments, functionCall.span());
            if (arguments.stream().allMatch(BoundLiteral.class::isInstance)) {
                List<Common.Value> literalValues = arguments.stream()
                        .map(BoundLiteral.class::cast)
                        .map(BoundLiteral::value)
                        .toList();
                return new BoundLiteral(evaluateScalarFunction(function, literalValues, resultType, functionCall.span()), functionCall.span());
            }
            return new BoundScalarFunction(function, arguments, resultType, functionCall.span());
        }

        private List<BoundExpression> bindArguments(List<SqlFrontend.Expression> expressions, Catalog.TableDefinition table,
                                                    List<TypeHint> hints) {
            List<BoundExpression> arguments = new ArrayList<>(expressions.size());
            for (int index = 0; index < expressions.size(); index++) {
                TypeHint hint = index < hints.size() ? hints.get(index) : TypeHint.UNKNOWN;
                arguments.add(bindExpression(expressions.get(index), table, hint));
            }
            return arguments;
        }

        private List<BoundExpression> bindCoalesceArguments(List<SqlFrontend.Expression> expressions, Catalog.TableDefinition table) {
            TypeHint commonHint = TypeHint.UNKNOWN;
            for (SqlFrontend.Expression expression : expressions) {
                if (expression instanceof SqlFrontend.ParameterExpression) {
                    continue;
                }
                BoundExpression bound = bindExpression(expression, table, TypeHint.UNKNOWN);
                commonHint = hintFromExpression(bound, table);
                if (commonHint.type() != null) {
                    break;
                }
            }
            List<BoundExpression> arguments = new ArrayList<>(expressions.size());
            for (SqlFrontend.Expression expression : expressions) {
                arguments.add(bindExpression(expression, table, commonHint));
            }
            return arguments;
        }

        private TypeHint parameterHint(SqlFrontend.BinaryOperator operator, BoundExpression other, Catalog.TableDefinition table) {
            return switch (operator) {
                case AND, OR -> TypeHint.of(Common.DataType.BOOLEAN);
                case ADD, SUB, MUL, DIV -> {
                    TypeHint hint = hintFromExpression(other, table);
                    yield hint.type() != null && hint.type().numeric() ? hint : TypeHint.of(Common.DataType.DECIMAL);
                }
                case EQ, NEQ, LT, LTE, GT, GTE -> {
                    TypeHint hint = hintFromExpression(other, table);
                    yield hint.type() == null ? TypeHint.UNKNOWN : hint;
                }
            };
        }

        private TypeHint hintFromExpression(BoundExpression expression, Catalog.TableDefinition table) {
            return switch (expression) {
                case BoundColumnRef columnRef -> {
                    Catalog.ColumnDefinition column = table.columns().get(columnRef.ordinal());
                    yield new TypeHint(column.type(), column.precision(), column.scale());
                }
                case BoundLiteral literal -> hintFromLiteral(literal.value());
                case BoundAggregate aggregate -> aggregate.type() == Common.DataType.DECIMAL
                        ? new TypeHint(Common.DataType.DECIMAL, Common.DataType.DECIMAL.defaultPrecision(),
                        Common.DataType.DECIMAL.defaultScale())
                        : TypeHint.of(aggregate.type());
                case BoundScalarFunction function -> function.type() == Common.DataType.DECIMAL
                        ? new TypeHint(Common.DataType.DECIMAL, Common.DataType.DECIMAL.defaultPrecision(),
                        Common.DataType.DECIMAL.defaultScale())
                        : TypeHint.of(function.type());
                case BoundBinary binary -> binary.type() == Common.DataType.DECIMAL
                        ? new TypeHint(Common.DataType.DECIMAL, Common.DataType.DECIMAL.defaultPrecision(),
                        Common.DataType.DECIMAL.defaultScale())
                        : TypeHint.of(binary.type());
                case BoundNextValue ignored -> TypeHint.of(Common.DataType.BIGINT);
            };
        }

        private TypeHint hintFromLiteral(Common.Value value) {
            if (value == null) {
                return TypeHint.UNKNOWN;
            }
            if (value.type() == Common.DataType.DECIMAL && !value.isNull()) {
                java.math.BigDecimal decimal = value.asDecimal();
                return new TypeHint(Common.DataType.DECIMAL, decimal.precision(), Math.max(decimal.scale(), 0));
            }
            return TypeHint.of(value.type());
        }

        private Common.DataType resolveScalarFunctionType(ScalarFunction function, String rawName, List<BoundExpression> arguments,
                                                          Common.SourceSpan span) {
            return switch (function) {
                case LOWER, UPPER -> {
                    requireExactArity(rawName, arguments, 1, span);
                    requireType(rawName, arguments.getFirst(), Common.DataType.TEXT, span);
                    yield Common.DataType.TEXT;
                }
                case LENGTH -> {
                    requireExactArity(rawName, arguments, 1, span);
                    requireType(rawName, arguments.getFirst(), Common.DataType.TEXT, span);
                    yield Common.DataType.BIGINT;
                }
                case ABS -> {
                    requireExactArity(rawName, arguments, 1, span);
                    Common.DataType type = arguments.getFirst().type();
                    if (!type.numeric()) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                rawName + " requires a numeric argument", span);
                    }
                    yield type;
                }
                case COALESCE -> {
                    if (arguments.isEmpty()) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                rawName + " requires at least one argument", span);
                    }
                    Common.DataType type = arguments.getFirst().type();
                    for (int index = 1; index < arguments.size(); index++) {
                        BoundExpression argument = arguments.get(index);
                        if (argument.type() != type && !(argument instanceof BoundLiteral)) {
                            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                    rawName + " arguments must share a common type", span);
                        }
                    }
                    yield type;
                }
                case TRIM -> {
                    requireExactArity(rawName, arguments, 1, span);
                    requireType(rawName, arguments.getFirst(), Common.DataType.TEXT, span);
                    yield Common.DataType.TEXT;
                }
                case SUBSTR -> {
                    if (arguments.size() < 2 || arguments.size() > 3) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                rawName + " expects two or three arguments", span);
                    }
                    requireType(rawName, arguments.getFirst(), Common.DataType.TEXT, span);
                    requireNumericType(rawName, arguments.get(1), span);
                    if (arguments.size() == 3) {
                        requireNumericType(rawName, arguments.get(2), span);
                    }
                    yield Common.DataType.TEXT;
                }
                case REPLACE -> {
                    requireExactArity(rawName, arguments, 3, span);
                    requireType(rawName, arguments.get(0), Common.DataType.TEXT, span);
                    requireType(rawName, arguments.get(1), Common.DataType.TEXT, span);
                    requireType(rawName, arguments.get(2), Common.DataType.TEXT, span);
                    yield Common.DataType.TEXT;
                }
            };
        }

        private void requireExactArity(String name, List<BoundExpression> arguments, int expected, Common.SourceSpan span) {
            if (arguments.size() != expected) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        name + " expects exactly " + expected + " argument(s)", span);
            }
        }

        private void requireType(String name, BoundExpression argument, Common.DataType expectedType, Common.SourceSpan span) {
            if (argument.type() != expectedType) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        name + " requires a " + expectedType + " argument", span);
            }
        }

        private void requireNumericType(String name, BoundExpression argument, Common.SourceSpan span) {
            if (!argument.type().numeric()) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        name + " requires a numeric argument", span);
            }
        }

        private Common.Value evaluateBinaryLiteral(Common.Value left, SqlFrontend.BinaryOperator operator, Common.Value right, Common.DataType type) {
            if (left == null || right == null || left.isNull() || right.isNull()) {
                return switch (operator) {
                    case EQ, NEQ, LT, LTE, GT, GTE, AND, OR -> Common.Value.nullValue(Common.DataType.BOOLEAN);
                    case ADD, SUB, MUL, DIV -> Common.Value.nullValue(type);
                };
            }
            return switch (operator) {
                case EQ -> Common.Value.bool(Common.Values.compare(left, right, "="));
                case NEQ -> Common.Value.bool(Common.Values.compare(left, right, "!="));
                case LT -> Common.Value.bool(Common.Values.compare(left, right, "<"));
                case LTE -> Common.Value.bool(Common.Values.compare(left, right, "<="));
                case GT -> Common.Value.bool(Common.Values.compare(left, right, ">"));
                case GTE -> Common.Value.bool(Common.Values.compare(left, right, ">="));
                case AND -> Common.Value.bool(and(left.asBoolean(), right.asBoolean()));
                case OR -> Common.Value.bool(or(left.asBoolean(), right.asBoolean()));
                case ADD -> type == Common.DataType.INTEGER
                        ? Common.Value.integer(left.asInt() + right.asInt())
                        : type == Common.DataType.BIGINT
                        ? Common.Value.bigint(left.asLong() + right.asLong())
                        : Common.Value.decimal(left.asDecimal().add(right.asDecimal()));
                case SUB -> type == Common.DataType.INTEGER
                        ? Common.Value.integer(left.asInt() - right.asInt())
                        : type == Common.DataType.BIGINT
                        ? Common.Value.bigint(left.asLong() - right.asLong())
                        : Common.Value.decimal(left.asDecimal().subtract(right.asDecimal()));
                case MUL -> type == Common.DataType.INTEGER
                        ? Common.Value.integer(left.asInt() * right.asInt())
                        : type == Common.DataType.BIGINT
                        ? Common.Value.bigint(left.asLong() * right.asLong())
                        : Common.Value.decimal(left.asDecimal().multiply(right.asDecimal()));
                case DIV -> type == Common.DataType.INTEGER
                        ? Common.Value.integer(left.asInt() / right.asInt())
                        : type == Common.DataType.BIGINT
                        ? Common.Value.bigint(left.asLong() / right.asLong())
                        : Common.Value.decimal(left.asDecimal().divide(right.asDecimal(), java.math.MathContext.DECIMAL128));
            };
        }

        private Common.DataType resolveNumericBinaryType(BoundExpression left, BoundExpression right, Common.SourceSpan span) {
            if (!left.type().numeric() || !right.type().numeric()) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Arithmetic operators require numeric operands", span);
            }
            if (left.type() == Common.DataType.DECIMAL || right.type() == Common.DataType.DECIMAL) {
                return Common.DataType.DECIMAL;
            }
            if (left.type() == Common.DataType.BIGINT || right.type() == Common.DataType.BIGINT) {
                return Common.DataType.BIGINT;
            }
            return Common.DataType.INTEGER;
        }

        private Common.Value evaluateScalarFunction(ScalarFunction function, List<Common.Value> arguments, Common.DataType type,
                                                    Common.SourceSpan span) {
            return switch (function) {
                case LOWER -> {
                    Common.Value value = arguments.getFirst();
                    yield value == null || value.isNull() ? Common.Value.nullValue(type) : Common.Value.text(value.asText().toLowerCase(Locale.ROOT));
                }
                case UPPER -> {
                    Common.Value value = arguments.getFirst();
                    yield value == null || value.isNull() ? Common.Value.nullValue(type) : Common.Value.text(value.asText().toUpperCase(Locale.ROOT));
                }
                case LENGTH -> {
                    Common.Value value = arguments.getFirst();
                    yield value == null || value.isNull() ? Common.Value.nullValue(type) : Common.Value.bigint((long) value.asText().length());
                }
                case ABS -> {
                    Common.Value value = arguments.getFirst();
                    if (value == null || value.isNull()) {
                        yield Common.Value.nullValue(type);
                    }
                    yield type == Common.DataType.INTEGER
                            ? Common.Value.integer(Math.abs(value.asInt()))
                            : Common.Value.bigint(Math.abs(value.asLong()));
                }
                case COALESCE -> {
                    for (Common.Value argument : arguments) {
                        if (argument != null && !argument.isNull()) {
                            yield Common.Values.coerce(argument, type);
                        }
                    }
                    yield Common.Value.nullValue(type);
                }
                case TRIM -> {
                    Common.Value value = arguments.getFirst();
                    yield value == null || value.isNull() ? Common.Value.nullValue(type) : Common.Value.text(value.asText().strip());
                }
                case SUBSTR -> {
                    Common.Value text = arguments.getFirst();
                    Common.Value start = arguments.get(1);
                    Common.Value length = arguments.size() > 2 ? arguments.get(2) : null;
                    yield substringValue(text, start, length, type);
                }
                case REPLACE -> {
                    Common.Value text = arguments.get(0);
                    Common.Value search = arguments.get(1);
                    Common.Value replacement = arguments.get(2);
                    if (text == null || text.isNull() || search == null || search.isNull() || replacement == null || replacement.isNull()) {
                        yield Common.Value.nullValue(type);
                    }
                    yield Common.Value.text(text.asText().replace(search.asText(), replacement.asText()));
                }
            };
        }

        private Common.Value substringValue(Common.Value text, Common.Value start, Common.Value length, Common.DataType type) {
            if (text == null || text.isNull() || start == null || start.isNull() || (length != null && length.isNull())) {
                return Common.Value.nullValue(type);
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

        private int substringStart(int inputLength, int sqlStart) {
            if (sqlStart > 0) {
                return Math.min(inputLength, sqlStart - 1);
            }
            if (sqlStart < 0) {
                return Math.max(0, inputLength + sqlStart);
            }
            return 0;
        }

        private Boolean and(Boolean left, Boolean right) {
            if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
                return false;
            }
            if (left == null || right == null) {
                return null;
            }
            return true;
        }

        private Boolean or(Boolean left, Boolean right) {
            if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
                return true;
            }
            if (left == null || right == null) {
                return null;
            }
            return false;
        }

        private BoundExpression simplifyBooleanBinary(BoundExpression left, SqlFrontend.BinaryOperator operator,
                                                      BoundExpression right, Common.SourceSpan span) {
            if (operator != SqlFrontend.BinaryOperator.AND && operator != SqlFrontend.BinaryOperator.OR) {
                return null;
            }
            BoundExpression simplified = simplifyBooleanBinary(left, operator, right, true, span);
            if (simplified != null) {
                return simplified;
            }
            return simplifyBooleanBinary(right, operator, left, false, span);
        }

        private BoundExpression simplifyBooleanBinary(BoundExpression candidateLiteral, SqlFrontend.BinaryOperator operator,
                                                      BoundExpression other, boolean literalOnLeft, Common.SourceSpan span) {
            if (!(candidateLiteral instanceof BoundLiteral literal)
                    || literal.type() != Common.DataType.BOOLEAN
                    || literal.value() == null
                    || literal.value().isNull()) {
                return null;
            }
            boolean value = literal.value().asBoolean();
            return switch (operator) {
                case AND -> value ? other : new BoundLiteral(Common.Value.bool(false), span);
                case OR -> value ? new BoundLiteral(Common.Value.bool(true), span) : other;
                default -> null;
            };
        }

        private boolean containsAggregate(BoundExpression expression) {
            if (expression == null) {
                return false;
            }
            return switch (expression) {
                case BoundAggregate ignored -> true;
                case BoundBinary binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
                case BoundScalarFunction function -> function.arguments().stream().anyMatch(this::containsAggregate);
                case BoundColumnRef columnRef -> false;
                case BoundLiteral literal -> false;
                case BoundNextValue nextValue -> false;
            };
        }

        private void validateAggregateProjection(List<BoundSelectItem> items, List<BoundExpression> groupBy) {
            for (BoundSelectItem item : items) {
                validateAggregateExpression(item.expression(), groupBy, "SELECT");
            }
        }

        private void validateAggregateExpression(BoundExpression expression, List<BoundExpression> groupBy, String context) {
            Set<String> groupKeys = new HashSet<>();
            groupBy.stream().map(this::expressionKey).forEach(groupKeys::add);
            validateAggregateExpression(expression, groupKeys, context);
        }

        private void validateAggregateExpression(BoundExpression expression, Set<String> groupKeys, String context) {
            switch (expression) {
                case null -> {
                }
                case BoundLiteral ignored -> {
                }
                case BoundAggregate ignored -> {
                }
                case BoundScalarFunction function -> {
                    if (groupKeys.contains(expressionKey(function))) {
                        return;
                    }
                    for (BoundExpression argument : function.arguments()) {
                        validateAggregateExpression(argument, groupKeys, context);
                    }
                }
                case BoundColumnRef columnRef -> {
                    if (!groupKeys.contains(expressionKey(columnRef))) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                context + " expression references non-grouped column " + columnRef.name(), columnRef.span());
                    }
                }
                case BoundBinary binary -> {
                    if (groupKeys.contains(expressionKey(binary))) {
                        return;
                    }
                    validateAggregateExpression(binary.left(), groupKeys, context);
                    validateAggregateExpression(binary.right(), groupKeys, context);
                }
                case BoundNextValue ignored -> {
                }
            }
        }

        private String expressionKey(BoundExpression expression) {
            return switch (expression) {
                case BoundColumnRef columnRef -> "COLUMN:" + columnRef.ordinal();
                case BoundLiteral literal -> "LITERAL:" + Common.Values.encodeValue(literal.value());
                case BoundAggregate aggregate -> "AGG:" + aggregate.function().name() + ":"
                        + (aggregate.argument() == null ? "*" : expressionKey(aggregate.argument()));
                case BoundScalarFunction function -> "FN:" + function.function().name() + "("
                        + function.arguments().stream().map(this::expressionKey).reduce((left, right) -> left + "," + right).orElse("") + ")";
                case BoundBinary binary -> "BINARY:" + binary.operator().name() + "("
                        + expressionKey(binary.left()) + "," + expressionKey(binary.right()) + ")";
                case BoundNextValue nextValue -> "NEXT:" + nextValue.sequence().id().value();
            };
        }
    }

    public static final class Optimizer {
        private final Catalog.CatalogSnapshot catalogSnapshot;
        private final Indexes.IndexSnapshot indexSnapshot;
        private final Storage.StorageSnapshot storageSnapshot;

        public Optimizer(Catalog.CatalogSnapshot catalogSnapshot, Indexes.IndexSnapshot indexSnapshot,
                         Storage.StorageSnapshot storageSnapshot) {
            this.catalogSnapshot = catalogSnapshot;
            this.indexSnapshot = indexSnapshot;
            this.storageSnapshot = storageSnapshot;
        }

        public PhysicalPlan optimize(BoundStatement statement) {
            return switch (statement) {
                case BoundCatalogStatement catalogStatement -> new CatalogPlan(catalogStatement);
                case BoundInsert insert -> new InsertPlan(insert);
                case BoundSelect select -> optimizeSelect(select);
                case BoundUpdate update -> new UpdatePlan(update);
                case BoundDelete delete -> new DeletePlan(delete);
                case ReferenceQueries.BoundReferenceQuery referenceQuery -> ReferenceQueries.optimize(referenceQuery, catalogSnapshot, storageSnapshot);
            };
        }

        private PhysicalPlan optimizeSelect(BoundSelect select) {
            BoundSelect simplified = simplifySelect(select);
            if (simplified.limit() != null && simplified.limit() <= 0) {
                return new EmptyResultPlan(simplified);
            }
            if (!simplified.aggregateQuery() && isAlwaysFalse(simplified.filter())) {
                return new EmptyResultPlan(simplified);
            }
            if (simplified.filter() instanceof BoundBinary comparison
                    && comparison.operator() == SqlFrontend.BinaryOperator.EQ
                    && comparison.left() instanceof BoundColumnRef columnRef
                    && comparison.right() instanceof BoundLiteral literal) {
                return Indexes.findSingleColumnIndex(catalogSnapshot, simplified.table(), columnRef.name())
                        .<PhysicalPlan>map(index -> new IndexLookupPlan(simplified, index, literal.value()))
                        .orElseGet(() -> new TableScanPlan(simplified));
            }
            return new TableScanPlan(simplified);
        }

        private BoundSelect simplifySelect(BoundSelect select) {
            if (select.filter() instanceof BoundLiteral literal
                    && literal.type() == Common.DataType.BOOLEAN
                    && literal.value() != null
                    && !literal.value().isNull()
                    && literal.value().asBoolean()) {
                return new BoundSelect(select.table(), select.items(), null, select.groupBy(), select.having(),
                        select.orderBy(), select.limit(), select.aggregateQuery(), select.span());
            }
            return select;
        }

        private boolean isAlwaysFalse(BoundExpression expression) {
            if (!(expression instanceof BoundLiteral literal) || literal.type() != Common.DataType.BOOLEAN) {
                return false;
            }
            return literal.value() == null || literal.value().isNull() || !literal.value().asBoolean();
        }
    }
}
