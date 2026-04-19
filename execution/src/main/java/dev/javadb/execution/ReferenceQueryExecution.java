package dev.javadb.execution;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.planner.ReferenceQueries;
import dev.javadb.storage.Storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

final class ReferenceQueryExecution {
    private static final int DEFAULT_SPILL_SORT_THRESHOLD = 64;

    private ReferenceQueryExecution() {
    }

    static Execution.ExecutionResult execute(ReferenceQueries.ReferenceQueryPlan plan, Execution.ExecutionContext context) {
        Runtime runtime = new Runtime(context, plan);
        QueryResult result = runtime.executeTop(plan.query().ast());
        List<Common.ResultRow> rows = result.rows().stream()
                .map(envelope -> new Common.ResultRow(envelope.projectedValues()))
                .toList();
        return Execution.ExecutionResult.query(new Common.TupleBatch(result.columns(), rows));
    }

    private static final class Runtime {
        private final Execution.ExecutionContext context;
        private final ReferenceQueries.ReferenceQueryPlan plan;

        private Runtime(Execution.ExecutionContext context, ReferenceQueries.ReferenceQueryPlan plan) {
            this.context = context;
            this.plan = plan;
        }

        private QueryResult executeTop(Map<String, Object> query) {
            return executeQuery(query, List.of(), true);
        }

        private QueryResult executeQuery(Map<String, Object> query, List<ScopeFrame> outerScopes, boolean topLevel) {
            Map<String, Object> body = asMap(query.get("body"));
            RelationResult relation = evaluateRelation(asMap(asMap(body.get("from_clause")).get("table")), outerScopes,
                    topLevel ? plan.joinStrategy() : ReferenceQueries.JoinStrategy.NESTED_LOOP);
            List<SourceRow> filteredRows = new ArrayList<>();
            Map<String, Object> whereClause = asMap(body.get("where_clause"));
            for (SourceRow row : relation.rows()) {
                context.checkCancelled();
                if (whereClause.isEmpty() || truthy(evaluateScalar(asMap(whereClause.get("condition")), row.scopes(), outerScopes))) {
                    filteredRows.add(row);
                }
            }

            List<Map<String, Object>> selectItems = normalizeSelectItems(asMap(body.get("select_list")));
            List<Map<String, Object>> groupBy = groupByExpressions(asMap(body.get("group_by_clause")));
            Map<String, Object> havingClause = asMap(body.get("having_clause"));
            List<Map<String, Object>> orderBy = asListOfMaps(asMap(body.get("order_by_clause")).get("items"));
            boolean aggregateQuery = !groupBy.isEmpty()
                    || selectItems.stream().anyMatch(item -> containsAggregate(asMap(item.get("expression"))))
                    || (!havingClause.isEmpty() && containsAggregate(asMap(havingClause.get("condition"))))
                    || orderBy.stream().anyMatch(item -> containsAggregate(asMap(item.get("expression"))));

            if (aggregateQuery) {
                validateAggregateQuery(selectItems, groupBy, havingClause, orderBy);
            }

            List<ProjectedRow> projectedRows = aggregateQuery
                    ? projectAggregateRows(selectItems, groupBy, havingClause, orderBy, filteredRows, outerScopes)
                    : projectScalarRows(selectItems, orderBy, filteredRows, outerScopes);

            if (!orderBy.isEmpty()) {
                List<Boolean> ascending = orderBy.stream()
                        .map(item -> !"DESC".equalsIgnoreCase(asText(item.get("direction"))))
                        .toList();
                projectedRows = sortProjectedRows(projectedRows, ascending, topLevel && plan.spillSort());
            }
            Integer limit = fetchLimit(asMap(body.get("fetch_clause")));
            if (limit != null && projectedRows.size() > limit) {
                projectedRows = new ArrayList<>(projectedRows.subList(0, limit));
            }
            List<Common.ResultColumn> columns = buildColumns(selectItems, relation.templates(),
                    outerScopes.stream().map(ScopeFrame::template).toList(), projectedRows);
            return new QueryResult(columns, projectedRows);
        }

        private RelationResult evaluateRelation(Map<String, Object> relation, List<ScopeFrame> outerScopes,
                                                ReferenceQueries.JoinStrategy joinStrategy) {
            String type = upperType(relation);
            return switch (type) {
                case "" -> new RelationResult(List.of(), List.of(new SourceRow(List.of())));
                case "TABLE" -> scanTable(relation);
                case "JOIN" -> evaluateJoin(relation, outerScopes, joinStrategy);
                case "SUBQUERY_TABLE" -> evaluateDerived(relation, outerScopes);
                default -> throw unsupported("Unsupported relation in reference execution: " + type);
            };
        }

        private RelationResult scanTable(Map<String, Object> relation) {
            Catalog.TableDefinition table = context.catalogSnapshot().requireTable(toQualifiedName(asText(relation.get("name"))));
            String alias = aliasOrDefault(relation.get("alias"), table.name().name());
            ScopeTemplate template = new ScopeTemplate(
                    qualifiersFor(alias, table.name().name()),
                    table.columns().stream().map(Catalog.ColumnDefinition::name).toList(),
                    table.columns().stream().map(Catalog.ColumnDefinition::type).toList());
            List<Storage.VisibleRow> visibleRows = Storage.visibleRows(context.committedStorage(), table.id(),
                    context.statementSnapshotSequence(), context.transactionState().currentDelta(table.id()));
            List<SourceRow> rows = new ArrayList<>(visibleRows.size());
            for (Storage.VisibleRow visibleRow : visibleRows) {
                context.checkCancelled();
                rows.add(new SourceRow(List.of(new ScopeFrame(template, visibleRow.values()))));
            }
            return new RelationResult(List.of(template), rows);
        }

        private RelationResult evaluateDerived(Map<String, Object> relation, List<ScopeFrame> outerScopes) {
            QueryResult result = executeQuery(queryFromSubquery(relation), outerScopes, false);
            String alias = aliasOrDefault(relation.get("alias"), "subquery");
            ScopeTemplate template = new ScopeTemplate(
                    List.of(alias.toLowerCase(Locale.ROOT)),
                    result.columns().stream().map(Common.ResultColumn::name).toList(),
                    result.columns().stream().map(Common.ResultColumn::type).toList());
            List<SourceRow> rows = new ArrayList<>(result.rows().size());
            for (ProjectedRow row : result.rows()) {
                context.checkCancelled();
                rows.add(new SourceRow(List.of(new ScopeFrame(template, row.projectedValues()))));
            }
            return new RelationResult(List.of(template), rows);
        }

        private RelationResult evaluateJoin(Map<String, Object> relation, List<ScopeFrame> outerScopes,
                                            ReferenceQueries.JoinStrategy joinStrategy) {
            String joinType = asText(relation.get("join_type")).toUpperCase(Locale.ROOT);
            RelationResult left = evaluateRelation(asMap(relation.get("left")), outerScopes, ReferenceQueries.JoinStrategy.NESTED_LOOP);
            RelationResult right = evaluateRelation(asMap(relation.get("right")), outerScopes, ReferenceQueries.JoinStrategy.NESTED_LOOP);
            return switch (joinType) {
                case "INNER", "CROSS" -> innerJoin(relation, left, right, outerScopes,
                        "CROSS".equals(joinType) ? ReferenceQueries.JoinStrategy.NESTED_LOOP : joinStrategy);
                case "LEFT" -> leftJoin(relation, left, right, outerScopes);
                case "RIGHT" -> rightJoin(relation, left, right, outerScopes);
                default -> throw unsupported("Unsupported join type in reference execution: " + joinType);
            };
        }

        private RelationResult innerJoin(Map<String, Object> relation, RelationResult left, RelationResult right,
                                         List<ScopeFrame> outerScopes, ReferenceQueries.JoinStrategy joinStrategy) {
            List<ScopeTemplate> templates = concatenateTemplates(left.templates(), right.templates());
            if (joinStrategy == ReferenceQueries.JoinStrategy.HASH && canHashJoin(relation)) {
                return hashJoin(relation, left, right, outerScopes, templates);
            }
            List<SourceRow> rows = new ArrayList<>();
            for (SourceRow leftRow : left.rows()) {
                context.checkCancelled();
                for (SourceRow rightRow : right.rows()) {
                    SourceRow joined = combineRows(leftRow, rightRow);
                    if (matchesJoinCondition(relation, joined.scopes(), outerScopes)) {
                        rows.add(joined);
                    }
                }
            }
            return new RelationResult(templates, rows);
        }

        private RelationResult leftJoin(Map<String, Object> relation, RelationResult left, RelationResult right,
                                        List<ScopeFrame> outerScopes) {
            List<ScopeTemplate> templates = concatenateTemplates(left.templates(), right.templates());
            List<SourceRow> rows = new ArrayList<>();
            List<ScopeFrame> nullRightFrames = nullFrames(right.templates());
            for (SourceRow leftRow : left.rows()) {
                context.checkCancelled();
                boolean matched = false;
                for (SourceRow rightRow : right.rows()) {
                    SourceRow joined = combineRows(leftRow, rightRow);
                    if (matchesJoinCondition(relation, joined.scopes(), outerScopes)) {
                        matched = true;
                        rows.add(joined);
                    }
                }
                if (!matched) {
                    rows.add(new SourceRow(concatenateFrames(leftRow.scopes(), nullRightFrames)));
                }
            }
            return new RelationResult(templates, rows);
        }

        private RelationResult rightJoin(Map<String, Object> relation, RelationResult left, RelationResult right,
                                         List<ScopeFrame> outerScopes) {
            List<ScopeTemplate> templates = concatenateTemplates(left.templates(), right.templates());
            List<SourceRow> rows = new ArrayList<>();
            List<ScopeFrame> nullLeftFrames = nullFrames(left.templates());
            for (SourceRow rightRow : right.rows()) {
                context.checkCancelled();
                boolean matched = false;
                for (SourceRow leftRow : left.rows()) {
                    SourceRow joined = combineRows(leftRow, rightRow);
                    if (matchesJoinCondition(relation, joined.scopes(), outerScopes)) {
                        matched = true;
                        rows.add(joined);
                    }
                }
                if (!matched) {
                    rows.add(new SourceRow(concatenateFrames(nullLeftFrames, rightRow.scopes())));
                }
            }
            return new RelationResult(templates, rows);
        }

        private RelationResult hashJoin(Map<String, Object> relation, RelationResult left, RelationResult right,
                                        List<ScopeFrame> outerScopes, List<ScopeTemplate> templates) {
            Map<String, Object> condition = asMap(relation.get("condition"));
            Map<String, Object> leftExpression = asMap(condition.get("left"));
            Map<String, Object> rightExpression = asMap(condition.get("right"));
            if (!referencesOnly(leftExpression, left.templates()) || !referencesOnly(rightExpression, right.templates())) {
                Map<String, Object> swappedLeft = rightExpression;
                Map<String, Object> swappedRight = leftExpression;
                if (referencesOnly(swappedLeft, left.templates()) && referencesOnly(swappedRight, right.templates())) {
                    leftExpression = swappedLeft;
                    rightExpression = swappedRight;
                } else {
                    return innerJoin(relation, left, right, outerScopes, ReferenceQueries.JoinStrategy.NESTED_LOOP);
                }
            }
            Map<String, List<SourceRow>> buckets = new LinkedHashMap<>();
            for (SourceRow rightRow : right.rows()) {
                context.checkCancelled();
                String key = keyFor(evaluateScalar(rightExpression, rightRow.scopes(), outerScopes));
                buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rightRow);
            }
            List<SourceRow> rows = new ArrayList<>();
            for (SourceRow leftRow : left.rows()) {
                context.checkCancelled();
                String key = keyFor(evaluateScalar(leftExpression, leftRow.scopes(), outerScopes));
                for (SourceRow rightRow : buckets.getOrDefault(key, List.of())) {
                    SourceRow joined = combineRows(leftRow, rightRow);
                    if (matchesJoinCondition(relation, joined.scopes(), outerScopes)) {
                        rows.add(joined);
                    }
                }
            }
            return new RelationResult(templates, rows);
        }

        private boolean matchesJoinCondition(Map<String, Object> relation, List<ScopeFrame> scopes, List<ScopeFrame> outerScopes) {
            if ("CROSS".equalsIgnoreCase(asText(relation.get("join_type")))) {
                return true;
            }
            Map<String, Object> condition = asMap(relation.get("condition"));
            return truthy(evaluateScalar(condition, scopes, outerScopes));
        }

        private List<ProjectedRow> projectScalarRows(List<Map<String, Object>> selectItems, List<Map<String, Object>> orderBy,
                                                     List<SourceRow> rows, List<ScopeFrame> outerScopes) {
            List<ProjectedRow> projected = new ArrayList<>();
            long ordinal = 0;
            for (SourceRow row : rows) {
                context.checkCancelled();
                List<Common.Value> values = new ArrayList<>(selectItems.size());
                for (Map<String, Object> item : selectItems) {
                    values.add(evaluateScalar(asMap(item.get("expression")), row.scopes(), outerScopes));
                }
                List<Common.Value> sortKeys = new ArrayList<>(orderBy.size());
                for (Map<String, Object> item : orderBy) {
                    sortKeys.add(evaluateScalar(asMap(item.get("expression")), row.scopes(), outerScopes));
                }
                projected.add(new ProjectedRow(values, sortKeys, ordinal++));
            }
            return projected;
        }

        private List<ProjectedRow> projectAggregateRows(List<Map<String, Object>> selectItems, List<Map<String, Object>> groupBy,
                                                        Map<String, Object> havingClause, List<Map<String, Object>> orderBy,
                                                        List<SourceRow> rows, List<ScopeFrame> outerScopes) {
            List<List<SourceRow>> groups = buildGroups(rows, groupBy, outerScopes);
            List<ProjectedRow> projected = new ArrayList<>();
            long ordinal = 0;
            for (List<SourceRow> group : groups) {
                context.checkCancelled();
                if (!havingClause.isEmpty() && !truthy(evaluateAggregate(asMap(havingClause.get("condition")), group, outerScopes))) {
                    continue;
                }
                List<Common.Value> values = new ArrayList<>(selectItems.size());
                for (Map<String, Object> item : selectItems) {
                    values.add(evaluateAggregate(asMap(item.get("expression")), group, outerScopes));
                }
                List<Common.Value> sortKeys = new ArrayList<>(orderBy.size());
                for (Map<String, Object> item : orderBy) {
                    sortKeys.add(evaluateAggregate(asMap(item.get("expression")), group, outerScopes));
                }
                projected.add(new ProjectedRow(values, sortKeys, ordinal++));
            }
            return projected;
        }

        private List<List<SourceRow>> buildGroups(List<SourceRow> rows, List<Map<String, Object>> groupBy, List<ScopeFrame> outerScopes) {
            if (groupBy.isEmpty()) {
                return List.of(new ArrayList<>(rows));
            }
            Map<String, List<SourceRow>> groups = new LinkedHashMap<>();
            for (SourceRow row : rows) {
                context.checkCancelled();
                List<Common.Value> keyValues = new ArrayList<>(groupBy.size());
                for (Map<String, Object> expression : groupBy) {
                    keyValues.add(evaluateScalar(expression, row.scopes(), outerScopes));
                }
                String key = keyValues.stream().map(this::keyFor).reduce((left, right) -> left + "|" + right).orElse("");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
            return new ArrayList<>(groups.values());
        }

        private void validateAggregateQuery(List<Map<String, Object>> selectItems, List<Map<String, Object>> groupBy,
                                            Map<String, Object> havingClause, List<Map<String, Object>> orderBy) {
            List<String> groupKeys = groupBy.stream().map(this::expressionKey).toList();
            for (Map<String, Object> item : selectItems) {
                validateAggregateExpression(asMap(item.get("expression")), groupKeys, "SELECT");
            }
            if (!havingClause.isEmpty()) {
                validateAggregateExpression(asMap(havingClause.get("condition")), groupKeys, "HAVING");
            }
            for (Map<String, Object> item : orderBy) {
                validateAggregateExpression(asMap(item.get("expression")), groupKeys, "ORDER BY");
            }
        }

        private void validateAggregateExpression(Map<String, Object> expression, List<String> groupKeys, String context) {
            if (groupKeys.contains(expressionKey(expression))) {
                return;
            }
            String type = upperType(expression);
            switch (type) {
                case "LITERAL" -> {
                }
                case "IDENTIFIER", "QUALIFIED_IDENTIFIER" -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        context + " expression references non-grouped column " + expressionKey(expression));
                case "BINARY_EXPRESSION" -> {
                    validateAggregateExpression(asMap(expression.get("left")), groupKeys, context);
                    validateAggregateExpression(asMap(expression.get("right")), groupKeys, context);
                }
                case "FUNCTION_CALL" -> {
                    if (isAggregateFunction(functionName(expression))) {
                        return;
                    }
                    for (Map<String, Object> argument : asListOfMaps(expression.get("arguments"))) {
                        validateAggregateExpression(argument, groupKeys, context);
                    }
                }
                case "EXISTS_CONDITION" -> throw unsupported("Aggregate queries with EXISTS are not supported in the reference execution path");
                default -> throw unsupported("Unsupported aggregate expression in reference execution: " + type);
            }
        }

        private List<Common.ResultColumn> buildColumns(List<Map<String, Object>> selectItems, List<ScopeTemplate> currentTemplates,
                                                       List<ScopeTemplate> outerTemplates, List<ProjectedRow> rows) {
            List<Common.ResultColumn> columns = new ArrayList<>(selectItems.size());
            for (int index = 0; index < selectItems.size(); index++) {
                Map<String, Object> item = selectItems.get(index);
                String name = selectAlias(item);
                Common.DataType type = rows.isEmpty()
                        ? inferExpressionType(asMap(item.get("expression")), currentTemplates, outerTemplates)
                        : firstNonNullType(rows, index, inferExpressionType(asMap(item.get("expression")), currentTemplates, outerTemplates));
                columns.add(new Common.ResultColumn(name, type));
            }
            return columns;
        }

        private Common.DataType firstNonNullType(List<ProjectedRow> rows, int index, Common.DataType fallback) {
            for (ProjectedRow row : rows) {
                Common.Value value = row.projectedValues().get(index);
                if (value != null) {
                    return value.type();
                }
            }
            return fallback;
        }

        private List<ProjectedRow> sortProjectedRows(List<ProjectedRow> rows, List<Boolean> ascending, boolean spill) {
            Comparator<ProjectedRow> comparator = projectedRowComparator(ascending);
            if (!spill || rows.size() < spillSortThreshold()) {
                rows.sort(comparator);
                return rows;
            }
            return spillSort(rows, comparator);
        }

        private List<ProjectedRow> spillSort(List<ProjectedRow> rows, Comparator<ProjectedRow> comparator) {
            List<Path> runs = new ArrayList<>();
            int chunkSize = spillSortThreshold();
            try {
                for (int start = 0; start < rows.size(); start += chunkSize) {
                    List<ProjectedRow> chunk = new ArrayList<>(rows.subList(start, Math.min(rows.size(), start + chunkSize)));
                    chunk.sort(comparator);
                    Path run = Files.createTempFile("javadb-sort-run-", ".tmp");
                    runs.add(run);
                    try (BufferedWriter writer = Files.newBufferedWriter(run, StandardCharsets.UTF_8)) {
                        for (ProjectedRow row : chunk) {
                            writer.write(serializeProjectedRow(row));
                            writer.newLine();
                        }
                    }
                }
                PriorityQueue<RunCursor> queue = new PriorityQueue<>((left, right) -> comparator.compare(left.row(), right.row()));
                List<BufferedReader> readers = new ArrayList<>();
                for (int index = 0; index < runs.size(); index++) {
                    BufferedReader reader = Files.newBufferedReader(runs.get(index), StandardCharsets.UTF_8);
                    readers.add(reader);
                    String line = reader.readLine();
                    if (line != null) {
                        queue.add(new RunCursor(index, deserializeProjectedRow(line)));
                    }
                }
                List<ProjectedRow> sorted = new ArrayList<>(rows.size());
                while (!queue.isEmpty()) {
                    context.checkCancelled();
                    RunCursor cursor = queue.poll();
                    sorted.add(cursor.row());
                    String line = readers.get(cursor.runIndex()).readLine();
                    if (line != null) {
                        queue.add(new RunCursor(cursor.runIndex(), deserializeProjectedRow(line)));
                    }
                }
                for (BufferedReader reader : readers) {
                    reader.close();
                }
                return sorted;
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to spill-sort reference query results", exception);
            } finally {
                for (Path run : runs) {
                    try {
                        Files.deleteIfExists(run);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private Comparator<ProjectedRow> projectedRowComparator(List<Boolean> ascending) {
            return (left, right) -> {
                for (int index = 0; index < left.sortKeys().size(); index++) {
                    int compare = Common.Values.nullsLastComparator().compare(left.sortKeys().get(index), right.sortKeys().get(index));
                    if (compare != 0) {
                        return ascending.get(index) ? compare : -compare;
                    }
                }
                return Long.compare(left.ordinal(), right.ordinal());
            };
        }

        private String serializeProjectedRow(ProjectedRow row) {
            return row.ordinal() + "|" + encodeValues(row.projectedValues()) + "|" + encodeValues(row.sortKeys());
        }

        private ProjectedRow deserializeProjectedRow(String line) {
            String[] parts = line.split("\\|", 3);
            long ordinal = Long.parseLong(parts[0]);
            List<Common.Value> projected = decodeValues(parts.length > 1 ? parts[1] : "");
            List<Common.Value> sortKeys = decodeValues(parts.length > 2 ? parts[2] : "");
            return new ProjectedRow(projected, sortKeys, ordinal);
        }

        private String encodeValues(List<Common.Value> values) {
            return values.stream().map(Common.Values::encodeValue).reduce((left, right) -> left + "," + right).orElse("");
        }

        private List<Common.Value> decodeValues(String text) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            String[] parts = text.split(",");
            List<Common.Value> values = new ArrayList<>(parts.length);
            for (String part : parts) {
                values.add(Common.Values.decodeValue(part));
            }
            return values;
        }

        private Common.Value evaluateScalar(Map<String, Object> expression, List<ScopeFrame> scopes, List<ScopeFrame> outerScopes) {
            return switch (upperType(expression)) {
                case "IDENTIFIER" -> resolveValue(null, asText(expression.get("value")), scopes, outerScopes);
                case "QUALIFIED_IDENTIFIER" -> {
                    List<String> parts = stringParts(expression.get("parts"));
                    if (parts.size() != 2) {
                        throw unsupported("Only two-part qualified identifiers are supported in reference execution");
                    }
                    yield resolveValue(parts.get(0), parts.get(1), scopes, outerScopes);
                }
                case "LITERAL" -> parseLiteral(expression);
                case "BINARY_EXPRESSION" -> evaluateBinary(
                        evaluateScalar(asMap(expression.get("left")), scopes, outerScopes),
                        asText(expression.get("operator")),
                        evaluateScalar(asMap(expression.get("right")), scopes, outerScopes));
                case "FUNCTION_CALL" -> evaluateFunction(functionName(expression), asListOfMaps(expression.get("arguments")).stream()
                        .map(argument -> evaluateScalar(argument, scopes, outerScopes))
                        .toList());
                case "EXISTS_CONDITION" -> Common.Value.bool(!executeQuery(queryFromSubquery(asMap(expression.get("subquery"))),
                        concatenateFrames(scopes, outerScopes), false).rows().isEmpty());
                case "SELECT_ITEM" -> evaluateScalar(asMap(expression.get("expression")), scopes, outerScopes);
                default -> throw unsupported("Unsupported scalar expression in reference execution: " + upperType(expression));
            };
        }

        private Common.Value evaluateAggregate(Map<String, Object> expression, List<SourceRow> group, List<ScopeFrame> outerScopes) {
            return switch (upperType(expression)) {
                case "IDENTIFIER" -> group.isEmpty()
                        ? Common.Value.text(null)
                        : resolveValue(null, asText(expression.get("value")), group.getFirst().scopes(), outerScopes);
                case "QUALIFIED_IDENTIFIER" -> {
                    List<String> parts = stringParts(expression.get("parts"));
                    if (group.isEmpty()) {
                        yield Common.Value.text(null);
                    }
                    yield resolveValue(parts.get(0), parts.get(1), group.getFirst().scopes(), outerScopes);
                }
                case "LITERAL" -> parseLiteral(expression);
                case "BINARY_EXPRESSION" -> evaluateBinary(
                        evaluateAggregate(asMap(expression.get("left")), group, outerScopes),
                        asText(expression.get("operator")),
                        evaluateAggregate(asMap(expression.get("right")), group, outerScopes));
                case "FUNCTION_CALL" -> {
                    String functionName = functionName(expression);
                    if (isAggregateFunction(functionName)) {
                        yield evaluateAggregateFunction(functionName, asListOfMaps(expression.get("arguments")), group, outerScopes);
                    }
                    List<Common.Value> arguments = asListOfMaps(expression.get("arguments")).stream()
                            .map(argument -> evaluateAggregate(argument, group, outerScopes))
                            .toList();
                    yield evaluateFunction(functionName, arguments);
                }
                case "SELECT_ITEM" -> evaluateAggregate(asMap(expression.get("expression")), group, outerScopes);
                default -> throw unsupported("Unsupported aggregate expression in reference execution: " + upperType(expression));
            };
        }

        private Common.Value evaluateAggregateFunction(String functionName, List<Map<String, Object>> arguments,
                                                       List<SourceRow> group, List<ScopeFrame> outerScopes) {
            return switch (functionName) {
                case "COUNT" -> {
                    if (arguments.size() != 1) {
                        throw semantic("COUNT expects exactly one argument");
                    }
                    if ("STAR".equalsIgnoreCase(asText(arguments.getFirst().get("type")))) {
                        yield Common.Value.bigint((long) group.size());
                    }
                    long count = 0;
                    for (SourceRow row : group) {
                        Common.Value value = evaluateScalar(arguments.getFirst(), row.scopes(), outerScopes);
                        if (value != null && !value.isNull()) {
                            count++;
                        }
                    }
                    yield Common.Value.bigint(count);
                }
                case "SUM" -> {
                    long total = 0;
                    Common.DataType type = null;
                    boolean sawValue = false;
                    for (SourceRow row : group) {
                        Common.Value value = evaluateScalar(arguments.getFirst(), row.scopes(), outerScopes);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        sawValue = true;
                        type = value.type();
                        total += numericValue(value);
                    }
                    if (!sawValue) {
                        yield Common.Value.nullValue(Common.DataType.BIGINT);
                    }
                    yield type == Common.DataType.INTEGER ? Common.Value.integer((int) total) : Common.Value.bigint(total);
                }
                case "MIN" -> {
                    Common.Value best = null;
                    for (SourceRow row : group) {
                        Common.Value value = evaluateScalar(arguments.getFirst(), row.scopes(), outerScopes);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        if (best == null || Common.Values.nullsLastComparator().compare(best, value) > 0) {
                            best = value;
                        }
                    }
                    yield best == null ? Common.Value.text(null) : best;
                }
                case "MAX" -> {
                    Common.Value best = null;
                    for (SourceRow row : group) {
                        Common.Value value = evaluateScalar(arguments.getFirst(), row.scopes(), outerScopes);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        if (best == null || Common.Values.nullsLastComparator().compare(best, value) < 0) {
                            best = value;
                        }
                    }
                    yield best == null ? Common.Value.text(null) : best;
                }
                case "AVG" -> {
                    long total = 0;
                    long count = 0;
                    for (SourceRow row : group) {
                        Common.Value value = evaluateScalar(arguments.getFirst(), row.scopes(), outerScopes);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        total += numericValue(value);
                        count++;
                    }
                    if (count == 0) {
                        yield Common.Value.nullValue(Common.DataType.BIGINT);
                    }
                    yield Common.Value.bigint(total / count);
                }
                default -> throw unsupported("Unsupported aggregate function in reference execution: " + functionName);
            };
        }

        private Common.Value evaluateFunction(String functionName, List<Common.Value> arguments) {
            return switch (functionName) {
                case "LOWER" -> nullPreserving(arguments.getFirst(), value -> Common.Value.text(value.asText().toLowerCase(Locale.ROOT)));
                case "UPPER" -> nullPreserving(arguments.getFirst(), value -> Common.Value.text(value.asText().toUpperCase(Locale.ROOT)));
                case "LENGTH" -> nullPreserving(arguments.getFirst(), value -> Common.Value.bigint((long) value.asText().length()));
                case "ABS" -> nullPreserving(arguments.getFirst(), value -> value.type() == Common.DataType.INTEGER
                        ? Common.Value.integer(Math.abs(value.asInt()))
                        : Common.Value.bigint(Math.abs(value.asLong())));
                case "COALESCE", "NVL" -> {
                    for (Common.Value argument : arguments) {
                        if (argument != null && !argument.isNull()) {
                            yield argument;
                        }
                    }
                    yield Common.Value.text(null);
                }
                case "TRIM" -> nullPreserving(arguments.getFirst(), value -> Common.Value.text(value.asText().strip()));
                case "SUBSTR", "SUBSTRING" -> substring(arguments);
                case "REPLACE" -> {
                    if (arguments.stream().anyMatch(argument -> argument == null || argument.isNull())) {
                        yield Common.Value.text(null);
                    }
                    yield Common.Value.text(arguments.get(0).asText().replace(arguments.get(1).asText(), arguments.get(2).asText()));
                }
                default -> throw unsupported("Unsupported scalar function in reference execution: " + functionName);
            };
        }

        private Common.Value nullPreserving(Common.Value argument, java.util.function.Function<Common.Value, Common.Value> mapper) {
            return argument == null || argument.isNull() ? Common.Value.text(null) : mapper.apply(argument);
        }

        private Common.Value substring(List<Common.Value> arguments) {
            if (arguments.size() < 2 || arguments.size() > 3) {
                throw semantic("SUBSTR expects two or three arguments");
            }
            Common.Value text = arguments.getFirst();
            Common.Value start = arguments.get(1);
            Common.Value length = arguments.size() > 2 ? arguments.get(2) : null;
            if (text == null || text.isNull() || start == null || start.isNull() || (length != null && length.isNull())) {
                return Common.Value.text(null);
            }
            String raw = text.asText();
            int begin = startIndex(raw.length(), (int) numericValue(start));
            int end = raw.length();
            if (length != null) {
                int requested = (int) numericValue(length);
                if (requested <= 0) {
                    return Common.Value.text("");
                }
                end = Math.min(raw.length(), begin + requested);
            }
            return Common.Value.text(raw.substring(begin, end));
        }

        private int startIndex(int inputLength, int sqlStart) {
            if (sqlStart > 0) {
                return Math.min(inputLength, sqlStart - 1);
            }
            if (sqlStart < 0) {
                return Math.max(0, inputLength + sqlStart);
            }
            return 0;
        }

        private Common.Value evaluateBinary(Common.Value left, String operator, Common.Value right) {
            return switch (operator) {
                case "=" -> Common.Value.bool(Common.Values.compare(left, right, "="));
                case "!=", "<>" -> Common.Value.bool(Common.Values.compare(left, right, "!="));
                case "<" -> Common.Value.bool(Common.Values.compare(left, right, "<"));
                case "<=" -> Common.Value.bool(Common.Values.compare(left, right, "<="));
                case ">" -> Common.Value.bool(Common.Values.compare(left, right, ">"));
                case ">=" -> Common.Value.bool(Common.Values.compare(left, right, ">="));
                case "AND" -> Common.Value.bool(and(left, right));
                case "OR" -> Common.Value.bool(or(left, right));
                case "+" -> arithmetic(left, right, true, false);
                case "-" -> arithmetic(left, right, false, false);
                case "*" -> arithmetic(left, right, true, true);
                case "/" -> divide(left, right);
                default -> throw unsupported("Unsupported operator in reference execution: " + operator);
            };
        }

        private Common.Value arithmetic(Common.Value left, Common.Value right, boolean add, boolean multiply) {
            if (left == null || right == null || left.isNull() || right.isNull()) {
                return Common.Value.nullValue(commonNumericType(left, right));
            }
            Common.DataType type = commonNumericType(left, right);
            long result = multiply
                    ? numericValue(left) * numericValue(right)
                    : add ? numericValue(left) + numericValue(right) : numericValue(left) - numericValue(right);
            return type == Common.DataType.INTEGER ? Common.Value.integer((int) result) : Common.Value.bigint(result);
        }

        private Common.Value divide(Common.Value left, Common.Value right) {
            if (left == null || right == null || left.isNull() || right.isNull()) {
                return Common.Value.nullValue(commonNumericType(left, right));
            }
            Common.DataType type = commonNumericType(left, right);
            long result = numericValue(left) / numericValue(right);
            return type == Common.DataType.INTEGER ? Common.Value.integer((int) result) : Common.Value.bigint(result);
        }

        private long numericValue(Common.Value value) {
            return value.type() == Common.DataType.INTEGER ? value.asInt() : value.asLong();
        }

        private Common.DataType commonNumericType(Common.Value left, Common.Value right) {
            Common.DataType leftType = left == null ? Common.DataType.BIGINT : left.type();
            Common.DataType rightType = right == null ? Common.DataType.BIGINT : right.type();
            return leftType == Common.DataType.INTEGER && rightType == Common.DataType.INTEGER
                    ? Common.DataType.INTEGER
                    : Common.DataType.BIGINT;
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

        private boolean truthy(Common.Value value) {
            return value != null && !value.isNull() && Boolean.TRUE.equals(value.asBoolean());
        }

        private Common.Value parseLiteral(Map<String, Object> literal) {
            String literalType = asText(literal.get("literal_type")).toUpperCase(Locale.ROOT);
            String value = asText(literal.get("value"));
            return switch (literalType) {
                case "NUMERIC_LITERAL" -> {
                    long parsed = Long.parseLong(value);
                    yield parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE
                            ? Common.Value.integer((int) parsed)
                            : Common.Value.bigint(parsed);
                }
                case "STRING_LITERAL" -> Common.Value.text(unquote(value));
                case "NULL_LITERAL" -> Common.Value.text(null);
                case "BOOLEAN_LITERAL" -> Common.Value.bool(Boolean.parseBoolean(value.toLowerCase(Locale.ROOT)));
                default -> throw unsupported("Unsupported literal in reference execution: " + literalType);
            };
        }

        private String unquote(String text) {
            if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
                return text.substring(1, text.length() - 1).replace("''", "'");
            }
            return text;
        }

        private Common.Value resolveValue(String qualifier, String column, List<ScopeFrame> scopes, List<ScopeFrame> outerScopes) {
            List<ScopeFrame> visibleScopes = concatenateFrames(scopes, outerScopes);
            Common.Value resolved = null;
            boolean found = false;
            for (ScopeFrame scope : visibleScopes) {
                if (qualifier != null && scope.template().qualifiers().stream().noneMatch(name -> name.equalsIgnoreCase(qualifier))) {
                    continue;
                }
                for (int index = 0; index < scope.template().columnNames().size(); index++) {
                    if (!scope.template().columnNames().get(index).equalsIgnoreCase(column)) {
                        continue;
                    }
                    if (found && qualifier == null) {
                        throw semantic("Ambiguous column reference: " + column);
                    }
                    found = true;
                    resolved = scope.values().get(index);
                }
            }
            if (!found) {
                throw semantic("Unknown column reference: " + (qualifier == null ? column : qualifier + "." + column));
            }
            return resolved;
        }

        private Common.DataType inferExpressionType(Map<String, Object> expression, List<ScopeTemplate> currentTemplates,
                                                    List<ScopeTemplate> outerTemplates) {
            return switch (upperType(expression)) {
                case "IDENTIFIER" -> resolveType(null, asText(expression.get("value")), currentTemplates, outerTemplates);
                case "QUALIFIED_IDENTIFIER" -> {
                    List<String> parts = stringParts(expression.get("parts"));
                    yield resolveType(parts.get(0), parts.get(1), currentTemplates, outerTemplates);
                }
                case "LITERAL" -> parseLiteral(expression).type();
                case "EXISTS_CONDITION" -> Common.DataType.BOOLEAN;
                case "BINARY_EXPRESSION" -> switch (asText(expression.get("operator"))) {
                    case "=", "!=", "<>", "<", "<=", ">", ">=", "AND", "OR" -> Common.DataType.BOOLEAN;
                    default -> inferExpressionType(asMap(expression.get("left")), currentTemplates, outerTemplates);
                };
                case "FUNCTION_CALL" -> inferFunctionType(functionName(expression), asListOfMaps(expression.get("arguments")),
                        currentTemplates, outerTemplates);
                case "SELECT_ITEM" -> inferExpressionType(asMap(expression.get("expression")), currentTemplates, outerTemplates);
                case "STAR" -> Common.DataType.BIGINT;
                default -> throw unsupported("Unsupported expression for type inference: " + upperType(expression));
            };
        }

        private Common.DataType inferFunctionType(String functionName, List<Map<String, Object>> arguments,
                                                  List<ScopeTemplate> currentTemplates, List<ScopeTemplate> outerTemplates) {
            return switch (functionName) {
                case "COUNT", "AVG", "LENGTH" -> Common.DataType.BIGINT;
                case "LOWER", "UPPER", "TRIM", "SUBSTR", "SUBSTRING", "REPLACE", "COALESCE", "NVL" -> arguments.isEmpty()
                        ? Common.DataType.TEXT
                        : inferExpressionType(arguments.getFirst(), currentTemplates, outerTemplates);
                case "ABS", "SUM", "MIN", "MAX" -> inferExpressionType(arguments.getFirst(), currentTemplates, outerTemplates);
                default -> Common.DataType.TEXT;
            };
        }

        private Common.DataType resolveType(String qualifier, String column, List<ScopeTemplate> currentTemplates,
                                            List<ScopeTemplate> outerTemplates) {
            List<ScopeTemplate> visible = new ArrayList<>(currentTemplates);
            visible.addAll(outerTemplates);
            Common.DataType resolved = null;
            boolean found = false;
            for (ScopeTemplate template : visible) {
                if (qualifier != null && template.qualifiers().stream().noneMatch(name -> name.equalsIgnoreCase(qualifier))) {
                    continue;
                }
                for (int index = 0; index < template.columnNames().size(); index++) {
                    if (!template.columnNames().get(index).equalsIgnoreCase(column)) {
                        continue;
                    }
                    if (found && qualifier == null) {
                        throw semantic("Ambiguous column reference: " + column);
                    }
                    found = true;
                    resolved = template.columnTypes().get(index);
                }
            }
            if (!found) {
                throw semantic("Unknown column reference: " + (qualifier == null ? column : qualifier + "." + column));
            }
            return resolved;
        }

        private boolean canHashJoin(Map<String, Object> relation) {
            Map<String, Object> condition = asMap(relation.get("condition"));
            return "BINARY_EXPRESSION".equalsIgnoreCase(asText(condition.get("type")))
                    && "=".equals(asText(condition.get("operator")));
        }

        private boolean referencesOnly(Map<String, Object> expression, List<ScopeTemplate> templates) {
            return switch (upperType(expression)) {
                case "IDENTIFIER" -> templateContainsColumn(null, asText(expression.get("value")), templates);
                case "QUALIFIED_IDENTIFIER" -> {
                    List<String> parts = stringParts(expression.get("parts"));
                    yield templateContainsColumn(parts.get(0), parts.get(1), templates);
                }
                default -> false;
            };
        }

        private boolean templateContainsColumn(String qualifier, String column, List<ScopeTemplate> templates) {
            for (ScopeTemplate template : templates) {
                if (qualifier != null && template.qualifiers().stream().noneMatch(name -> name.equalsIgnoreCase(qualifier))) {
                    continue;
                }
                for (String candidate : template.columnNames()) {
                    if (candidate.equalsIgnoreCase(column)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String expressionKey(Map<String, Object> expression) {
            return switch (upperType(expression)) {
                case "IDENTIFIER" -> "COLUMN:" + asText(expression.get("value")).toLowerCase(Locale.ROOT);
                case "QUALIFIED_IDENTIFIER" -> "COLUMN:" + String.join(".", stringParts(expression.get("parts")));
                case "LITERAL" -> "LITERAL:" + Common.Values.encodeValue(parseLiteral(expression));
                case "BINARY_EXPRESSION" -> "BINARY:" + asText(expression.get("operator")) + "("
                        + expressionKey(asMap(expression.get("left"))) + "," + expressionKey(asMap(expression.get("right"))) + ")";
                case "FUNCTION_CALL" -> functionName(expression) + "(" + asListOfMaps(expression.get("arguments")).stream()
                        .map(this::expressionKey).reduce((left, right) -> left + "," + right).orElse("") + ")";
                case "SELECT_ITEM" -> expressionKey(asMap(expression.get("expression")));
                default -> upperType(expression);
            };
        }

        private boolean containsAggregate(Map<String, Object> expression) {
            return switch (upperType(expression)) {
                case "FUNCTION_CALL" -> isAggregateFunction(functionName(expression))
                        || asListOfMaps(expression.get("arguments")).stream().anyMatch(this::containsAggregate);
                case "BINARY_EXPRESSION" -> containsAggregate(asMap(expression.get("left")))
                        || containsAggregate(asMap(expression.get("right")));
                case "SELECT_ITEM" -> containsAggregate(asMap(expression.get("expression")));
                default -> false;
            };
        }

        private boolean isAggregateFunction(String name) {
            return switch (name) {
                case "COUNT", "SUM", "MIN", "MAX", "AVG" -> true;
                default -> false;
            };
        }

        private String functionName(Map<String, Object> expression) {
            Object raw = expression.get("name");
            if (raw instanceof Map<?, ?> map) {
                return asText(((Map<?, ?>) map).get("value")).toUpperCase(Locale.ROOT);
            }
            return asText(raw).toUpperCase(Locale.ROOT);
        }

        private String keyFor(Common.Value value) {
            return Common.Values.encodeValue(value == null ? Common.Value.text(null) : value);
        }

        private Integer fetchLimit(Map<String, Object> fetchClause) {
            if (fetchClause.isEmpty()) {
                return null;
            }
            Map<String, Object> value = asMap(fetchClause.get("value"));
            if (!value.isEmpty()) {
                return parseLiteral(value).asInt();
            }
            return null;
        }

        private Common.DatabaseException semantic(String message) {
            return new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, message);
        }

        private Common.DatabaseException unsupported(String message) {
            return new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, message);
        }

        private int spillSortThreshold() {
            return Integer.getInteger("javadb.execution.sortSpillThresholdRows", DEFAULT_SPILL_SORT_THRESHOLD);
        }
    }

    private static String selectAlias(Map<String, Object> item) {
        Map<String, Object> alias = asMap(item.get("alias"));
        if (!alias.isEmpty()) {
            return asText(alias.get("name")).toLowerCase(Locale.ROOT);
        }
        Map<String, Object> expression = asMap(item.get("expression"));
        return switch (upperType(expression)) {
            case "IDENTIFIER" -> asText(expression.get("value")).toLowerCase(Locale.ROOT);
            case "QUALIFIED_IDENTIFIER" -> {
                List<String> parts = stringParts(expression.get("parts"));
                yield parts.getLast().toLowerCase(Locale.ROOT);
            }
            case "FUNCTION_CALL" -> functionName(expression).toLowerCase(Locale.ROOT);
            default -> upperType(expression).toLowerCase(Locale.ROOT);
        };
    }

    private static String functionName(Map<String, Object> expression) {
        Object raw = expression.get("name");
        if (raw instanceof Map<?, ?> map) {
            return asText(map.get("value")).toUpperCase(Locale.ROOT);
        }
        return asText(raw).toUpperCase(Locale.ROOT);
    }

    private static List<Map<String, Object>> normalizeSelectItems(Map<String, Object> selectList) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : asListOfMaps(selectList.get("items"))) {
            if ("SELECT_ITEM".equalsIgnoreCase(asText(item.get("type")))) {
                items.add(item);
            } else {
                items.add(Map.of("type", "SELECT_ITEM", "expression", item, "alias", Map.of()));
            }
        }
        return items;
    }

    private static List<Map<String, Object>> groupByExpressions(Map<String, Object> groupByClause) {
        List<Map<String, Object>> expressions = new ArrayList<>();
        for (Map<String, Object> element : asListOfMaps(groupByClause.get("elements"))) {
            if (!"GROUP_BY_EXPRESSION".equalsIgnoreCase(asText(element.get("type")))) {
                throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Unsupported GROUP BY element in reference execution: " + asText(element.get("type")));
            }
            expressions.add(asMap(element.get("expression")));
        }
        return expressions;
    }

    private static Map<String, Object> queryFromSubquery(Map<String, Object> wrapper) {
        if ("SUBQUERY_TABLE".equalsIgnoreCase(asText(wrapper.get("type")))) {
            return queryFromSubquery(asMap(wrapper.get("subquery")));
        }
        if ("SUBQUERY_EXPRESSION".equalsIgnoreCase(asText(wrapper.get("type")))) {
            return asMap(wrapper.get("query"));
        }
        return Map.of();
    }

    private static Catalog.QualifiedName toQualifiedName(String text) {
        String[] parts = text.toLowerCase(Locale.ROOT).split("\\.", 2);
        return parts.length == 2 ? new Catalog.QualifiedName(parts[0], parts[1]) : new Catalog.QualifiedName("public", parts[0]);
    }

    private static String aliasOrDefault(Object aliasNode, String fallback) {
        Map<String, Object> alias = asMap(aliasNode);
        return alias.isEmpty() ? fallback.toLowerCase(Locale.ROOT) : asText(alias.get("name")).toLowerCase(Locale.ROOT);
    }

    private static List<String> qualifiersFor(String alias, String tableName) {
        List<String> qualifiers = new ArrayList<>();
        qualifiers.add(alias.toLowerCase(Locale.ROOT));
        if (!alias.equalsIgnoreCase(tableName)) {
            qualifiers.add(tableName.toLowerCase(Locale.ROOT));
        }
        return qualifiers;
    }

    private static List<ScopeTemplate> concatenateTemplates(List<ScopeTemplate> left, List<ScopeTemplate> right) {
        List<ScopeTemplate> templates = new ArrayList<>(left.size() + right.size());
        templates.addAll(left);
        templates.addAll(right);
        return templates;
    }

    private static List<ScopeFrame> concatenateFrames(List<ScopeFrame> left, List<ScopeFrame> right) {
        List<ScopeFrame> scopes = new ArrayList<>(left.size() + right.size());
        scopes.addAll(left);
        scopes.addAll(right);
        return scopes;
    }

    private static SourceRow combineRows(SourceRow left, SourceRow right) {
        return new SourceRow(concatenateFrames(left.scopes(), right.scopes()));
    }

    private static List<ScopeFrame> nullFrames(List<ScopeTemplate> templates) {
        List<ScopeFrame> frames = new ArrayList<>(templates.size());
        for (ScopeTemplate template : templates) {
            frames.add(new ScopeFrame(template, nullValues(template.columnTypes().size())));
        }
        return frames;
    }

    private static List<Common.Value> nullValues(int size) {
        List<Common.Value> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(Common.Value.text(null));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>(raw.size());
        for (Object item : raw) {
            values.add(asMap(item));
        }
        return values;
    }

    private static List<String> stringParts(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(raw.size());
        for (Object item : raw) {
            parts.add(asText(item).toLowerCase(Locale.ROOT));
        }
        return parts;
    }

    private static String upperType(Map<String, Object> node) {
        return asText(node.get("type")).toUpperCase(Locale.ROOT);
    }

    private static String asText(Object value) {
        return Objects.toString(value, "");
    }

    private record QueryResult(List<Common.ResultColumn> columns, List<ProjectedRow> rows) {
    }

    private record RelationResult(List<ScopeTemplate> templates, List<SourceRow> rows) {
    }

    private record ScopeTemplate(List<String> qualifiers, List<String> columnNames, List<Common.DataType> columnTypes) {
        private ScopeTemplate {
            qualifiers = List.copyOf(qualifiers);
            columnNames = List.copyOf(columnNames);
            columnTypes = List.copyOf(columnTypes);
        }
    }

    private record ScopeFrame(ScopeTemplate template, List<Common.Value> values) {
        private ScopeFrame {
            values = List.copyOf(values);
        }
    }

    private record SourceRow(List<ScopeFrame> scopes) {
        private SourceRow {
            scopes = List.copyOf(scopes);
        }
    }

    private record ProjectedRow(List<Common.Value> projectedValues, List<Common.Value> sortKeys, long ordinal) {
        private ProjectedRow {
            projectedValues = List.copyOf(projectedValues);
            sortKeys = List.copyOf(sortKeys);
        }
    }

    private record RunCursor(int runIndex, ProjectedRow row) {
    }
}
