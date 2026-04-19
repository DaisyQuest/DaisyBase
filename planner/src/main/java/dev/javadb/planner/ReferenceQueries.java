package dev.javadb.planner;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.sql.SqlFrontend;
import dev.javadb.storage.Storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ReferenceQueries {
    private static final int DEFAULT_HASH_JOIN_THRESHOLD = 8;
    private static final int DEFAULT_SPILL_SORT_THRESHOLD = 64;

    private ReferenceQueries() {
    }

    public record BoundReferenceQuery(String dialect, String externalType, String summary, Map<String, Object> ast,
                                      Common.SourceSpan span) implements Planner.BoundStatement {
        public BoundReferenceQuery {
            ast = ast == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ast));
        }
    }

    public enum JoinStrategy {
        NONE,
        NESTED_LOOP,
        HASH
    }

    public record ReferenceQueryPlan(BoundReferenceQuery query, JoinStrategy joinStrategy, boolean spillSort,
                                     int tableCount, long estimatedRows, boolean correlatedSubquery) implements Planner.PhysicalPlan {
        @Override
        public String explain() {
            return "ReferenceQuery(type=" + query.externalType()
                    + ", summary=" + query.summary()
                    + ", join=" + joinStrategy
                    + ", spillSort=" + spillSort
                    + ", tables=" + tableCount
                    + ", estimatedRows=" + estimatedRows
                    + ", correlatedSubquery=" + correlatedSubquery
                    + ")";
        }
    }

    static BoundReferenceQuery bind(Catalog.CatalogSnapshot catalogSnapshot, SqlFrontend.ReferenceStatement statement) {
        if (!"QUERY_EXPRESSION".equalsIgnoreCase(statement.externalType())) {
            throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                    "Reference statement type is not executable yet: " + statement.externalType(), statement.span());
        }
        validateQuery(statement.ast(), catalogSnapshot, false);
        return new BoundReferenceQuery(statement.dialect(), statement.externalType(), statement.summary(), statement.ast(), statement.span());
    }

    static ReferenceQueryPlan optimize(BoundReferenceQuery query, Catalog.CatalogSnapshot catalogSnapshot,
                                       Storage.StorageSnapshot storageSnapshot) {
        QueryFacts facts = inspectQuery(query.ast(), catalogSnapshot, storageSnapshot);
        boolean spillSort = facts.hasOrderBy() && facts.estimatedRows() >= spillSortThreshold();
        return new ReferenceQueryPlan(query, facts.joinStrategy(), spillSort, facts.tableCount(),
                facts.estimatedRows(), facts.correlatedSubquery());
    }

    private static void validateQuery(Map<String, Object> query, Catalog.CatalogSnapshot catalogSnapshot, boolean insideSubquery) {
        requireType(query, "QUERY_EXPRESSION");
        if (query.get("with_clause") != null) {
            throw unsupported("WITH queries are not supported in the reference execution path");
        }
        if (!asListOfMaps(query.get("set_operations")).isEmpty()) {
            throw unsupported("Set operations are not supported in the reference execution path");
        }
        Map<String, Object> body = asMap(query.get("body"));
        requireType(body, "SELECT_STATEMENT");
        if (body.get("offset_clause") != null) {
            throw unsupported("OFFSET is not supported in the reference execution path");
        }
        Map<String, Object> fromClause = asMap(body.get("from_clause"));
        if (!fromClause.isEmpty()) {
            validateRelation(asMap(fromClause.get("table")), catalogSnapshot, insideSubquery);
        }
        for (Map<String, Object> item : normalizeSelectItems(asMap(body.get("select_list")))) {
            validateExpression(asMap(item.get("expression")), catalogSnapshot, insideSubquery);
        }
        Map<String, Object> whereClause = asMap(body.get("where_clause"));
        if (!whereClause.isEmpty()) {
            validateExpression(asMap(whereClause.get("condition")), catalogSnapshot, insideSubquery);
        }
        Map<String, Object> groupBy = asMap(body.get("group_by_clause"));
        for (Map<String, Object> element : asListOfMaps(groupBy.get("elements"))) {
            validateExpression(groupElementExpression(element), catalogSnapshot, insideSubquery);
        }
        Map<String, Object> havingClause = asMap(body.get("having_clause"));
        if (!havingClause.isEmpty()) {
            validateExpression(asMap(havingClause.get("condition")), catalogSnapshot, insideSubquery);
        }
        Map<String, Object> orderBy = asMap(body.get("order_by_clause"));
        for (Map<String, Object> item : asListOfMaps(orderBy.get("items"))) {
            validateExpression(asMap(item.get("expression")), catalogSnapshot, insideSubquery);
        }
        Map<String, Object> fetchClause = asMap(body.get("fetch_clause"));
        if (!fetchClause.isEmpty()) {
            validateExpression(asMap(fetchClause.get("value")), catalogSnapshot, insideSubquery);
        }
    }

    private static void validateRelation(Map<String, Object> relation, Catalog.CatalogSnapshot catalogSnapshot, boolean insideSubquery) {
        String type = upperType(relation);
        switch (type) {
            case "TABLE" -> catalogSnapshot.requireTable(toQualifiedName(asText(relation.get("name"))));
            case "JOIN" -> {
                validateRelation(asMap(relation.get("left")), catalogSnapshot, insideSubquery);
                validateRelation(asMap(relation.get("right")), catalogSnapshot, insideSubquery);
                if (relation.get("using") != null) {
                    throw unsupported("JOIN ... USING is not supported in the reference execution path");
                }
                if ("FULL".equalsIgnoreCase(asText(relation.get("join_type")))) {
                    throw unsupported("FULL OUTER JOIN is not supported in the reference execution path");
                }
                validateExpression(asMap(relation.get("condition")), catalogSnapshot, insideSubquery);
            }
            case "SUBQUERY_TABLE" -> validateQuery(queryFromSubquery(relation), catalogSnapshot, true);
            default -> throw unsupported("Unsupported relation in reference execution path: " + type);
        }
    }

    private static void validateExpression(Map<String, Object> expression, Catalog.CatalogSnapshot catalogSnapshot, boolean insideSubquery) {
        String type = upperType(expression);
        switch (type) {
            case "", "IDENTIFIER", "QUALIFIED_IDENTIFIER", "LITERAL", "STAR" -> {
            }
            case "SELECT_ITEM" -> validateExpression(asMap(expression.get("expression")), catalogSnapshot, insideSubquery);
            case "BINARY_EXPRESSION" -> {
                validateExpression(asMap(expression.get("left")), catalogSnapshot, insideSubquery);
                validateExpression(asMap(expression.get("right")), catalogSnapshot, insideSubquery);
            }
            case "FUNCTION_CALL" -> {
                if (Boolean.TRUE.equals(expression.get("distinct"))) {
                    throw unsupported("DISTINCT aggregate/function calls are not supported in the reference execution path");
                }
                for (Map<String, Object> argument : asListOfMaps(expression.get("arguments"))) {
                    validateExpression(argument, catalogSnapshot, insideSubquery);
                }
            }
            case "EXISTS_CONDITION" -> validateQuery(queryFromSubquery(asMap(expression.get("subquery"))), catalogSnapshot, true);
            case "SUBQUERY_EXPRESSION" -> {
                if (!insideSubquery) {
                    throw unsupported("Scalar subquery expressions are not supported in the reference execution path");
                }
                validateQuery(queryFromSubquery(expression), catalogSnapshot, true);
            }
            default -> throw unsupported("Unsupported expression in reference execution path: " + type);
        }
    }

    private static QueryFacts inspectQuery(Map<String, Object> query, Catalog.CatalogSnapshot catalogSnapshot,
                                           Storage.StorageSnapshot storageSnapshot) {
        Map<String, Object> body = asMap(query.get("body"));
        RelationFacts relationFacts = inspectRelation(asMap(asMap(body.get("from_clause")).get("table")), catalogSnapshot, storageSnapshot);
        long estimatedRows = relationFacts.estimatedRows();
        if (body.get("where_clause") != null) {
            estimatedRows = Math.max(1, estimatedRows / 2);
        }
        if (!asMap(body.get("group_by_clause")).isEmpty()) {
            estimatedRows = Math.max(1, estimatedRows / 2);
        }
        Integer limit = fetchLimit(asMap(body.get("fetch_clause")));
        if (limit != null) {
            estimatedRows = Math.min(estimatedRows, limit);
        }
        boolean correlatedSubquery = relationFacts.correlatedSubquery()
                || containsCorrelatedSubqueryInExpressions(body)
                || !asMap(body.get("having_clause")).isEmpty();
        return new QueryFacts(relationFacts.joinStrategy(), relationFacts.tableCount(), Math.max(estimatedRows, 1),
                correlatedSubquery, body.get("order_by_clause") != null);
    }

    private static boolean containsCorrelatedSubqueryInExpressions(Map<String, Object> body) {
        for (Map<String, Object> item : normalizeSelectItems(asMap(body.get("select_list")))) {
            if (containsSubquery(asMap(item.get("expression")))) {
                return true;
            }
        }
        Map<String, Object> where = asMap(body.get("where_clause"));
        if (!where.isEmpty() && containsSubquery(asMap(where.get("condition")))) {
            return true;
        }
        Map<String, Object> having = asMap(body.get("having_clause"));
        if (!having.isEmpty() && containsSubquery(asMap(having.get("condition")))) {
            return true;
        }
        Map<String, Object> orderBy = asMap(body.get("order_by_clause"));
        for (Map<String, Object> item : asListOfMaps(orderBy.get("items"))) {
            if (containsSubquery(asMap(item.get("expression")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSubquery(Map<String, Object> expression) {
        return switch (upperType(expression)) {
            case "EXISTS_CONDITION", "SUBQUERY_EXPRESSION" -> true;
            case "SELECT_ITEM" -> containsSubquery(asMap(expression.get("expression")));
            case "BINARY_EXPRESSION" -> containsSubquery(asMap(expression.get("left"))) || containsSubquery(asMap(expression.get("right")));
            case "FUNCTION_CALL" -> asListOfMaps(expression.get("arguments")).stream().anyMatch(ReferenceQueries::containsSubquery);
            default -> false;
        };
    }

    private static RelationFacts inspectRelation(Map<String, Object> relation, Catalog.CatalogSnapshot catalogSnapshot,
                                                 Storage.StorageSnapshot storageSnapshot) {
        String type = upperType(relation);
        return switch (type) {
            case "" -> new RelationFacts(JoinStrategy.NONE, 0, 1, false);
            case "TABLE" -> {
                Catalog.TableDefinition table = catalogSnapshot.requireTable(toQualifiedName(asText(relation.get("name"))));
                long rows = Storage.visibleRows(storageSnapshot, table.id(), Long.MAX_VALUE, null).size();
                yield new RelationFacts(JoinStrategy.NONE, 1, Math.max(rows, 1), false);
            }
            case "SUBQUERY_TABLE" -> {
                QueryFacts subqueryFacts = inspectQuery(queryFromSubquery(relation), catalogSnapshot, storageSnapshot);
                yield new RelationFacts(subqueryFacts.joinStrategy(), subqueryFacts.tableCount(), subqueryFacts.estimatedRows(),
                        true || subqueryFacts.correlatedSubquery());
            }
            case "JOIN" -> {
                RelationFacts left = inspectRelation(asMap(relation.get("left")), catalogSnapshot, storageSnapshot);
                RelationFacts right = inspectRelation(asMap(relation.get("right")), catalogSnapshot, storageSnapshot);
                JoinStrategy strategy = chooseJoinStrategy(relation, left, right);
                long estimatedRows = switch (asText(relation.get("join_type")).toUpperCase(Locale.ROOT)) {
                    case "LEFT", "RIGHT" -> Math.max(left.estimatedRows(), right.estimatedRows());
                    case "CROSS" -> left.estimatedRows() * right.estimatedRows();
                    default -> Math.max(1, Math.min(left.estimatedRows(), right.estimatedRows()) * 2);
                };
                yield new RelationFacts(strategy, left.tableCount() + right.tableCount(), estimatedRows,
                        left.correlatedSubquery() || right.correlatedSubquery());
            }
            default -> throw unsupported("Unsupported relation in reference optimizer: " + type);
        };
    }

    private static JoinStrategy chooseJoinStrategy(Map<String, Object> relation, RelationFacts left, RelationFacts right) {
        if (!"INNER".equalsIgnoreCase(asText(relation.get("join_type")))) {
            return JoinStrategy.NESTED_LOOP;
        }
        if (!isEqualityJoin(asMap(relation.get("condition")))) {
            return JoinStrategy.NESTED_LOOP;
        }
        return Math.min(left.estimatedRows(), right.estimatedRows()) >= hashJoinThreshold() ? JoinStrategy.HASH : JoinStrategy.NESTED_LOOP;
    }

    private static boolean isEqualityJoin(Map<String, Object> expression) {
        if (!"BINARY_EXPRESSION".equalsIgnoreCase(asText(expression.get("type")))) {
            return false;
        }
        return "=".equals(asText(expression.get("operator")))
                && isColumnExpression(asMap(expression.get("left")))
                && isColumnExpression(asMap(expression.get("right")));
    }

    private static boolean isColumnExpression(Map<String, Object> expression) {
        String type = upperType(expression);
        return "IDENTIFIER".equals(type) || "QUALIFIED_IDENTIFIER".equals(type);
    }

    private static int hashJoinThreshold() {
        return Integer.getInteger("javadb.execution.hashJoinThresholdRows", DEFAULT_HASH_JOIN_THRESHOLD);
    }

    private static int spillSortThreshold() {
        return Integer.getInteger("javadb.execution.sortSpillThresholdRows", DEFAULT_SPILL_SORT_THRESHOLD);
    }

    private static Integer fetchLimit(Map<String, Object> fetchClause) {
        if (fetchClause.isEmpty()) {
            return null;
        }
        Map<String, Object> value = asMap(fetchClause.get("value"));
        if ("LITERAL".equalsIgnoreCase(asText(value.get("type")))) {
            return Integer.parseInt(asText(value.get("value")));
        }
        return null;
    }

    private static Map<String, Object> groupElementExpression(Map<String, Object> element) {
        if ("GROUP_BY_EXPRESSION".equalsIgnoreCase(asText(element.get("type")))) {
            return asMap(element.get("expression"));
        }
        throw unsupported("Unsupported GROUP BY element in reference execution path: " + upperType(element));
    }

    private static List<Map<String, Object>> normalizeSelectItems(Map<String, Object> selectList) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : asListOfMaps(selectList.get("items"))) {
            if ("SELECT_ITEM".equalsIgnoreCase(asText(item.get("type")))) {
                items.add(item);
            } else {
                LinkedHashMap<String, Object> synthetic = new LinkedHashMap<>();
                synthetic.put("type", "SELECT_ITEM");
                synthetic.put("expression", item);
                synthetic.put("alias", null);
                items.add(Collections.unmodifiableMap(synthetic));
            }
        }
        return items;
    }

    private static Map<String, Object> queryFromSubquery(Map<String, Object> subqueryWrapper) {
        if ("SUBQUERY_TABLE".equalsIgnoreCase(asText(subqueryWrapper.get("type")))) {
            return queryFromSubquery(asMap(subqueryWrapper.get("subquery")));
        }
        if ("SUBQUERY_EXPRESSION".equalsIgnoreCase(asText(subqueryWrapper.get("type")))) {
            return asMap(subqueryWrapper.get("query"));
        }
        return Map.of();
    }

    private static Catalog.QualifiedName toQualifiedName(String text) {
        String[] parts = text.toLowerCase(Locale.ROOT).split("\\.", 2);
        return parts.length == 2 ? new Catalog.QualifiedName(parts[0], parts[1]) : new Catalog.QualifiedName("public", parts[0]);
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

    private static String asText(Object value) {
        return Objects.toString(value, "");
    }

    private static String upperType(Map<String, Object> node) {
        return asText(node.get("type")).toUpperCase(Locale.ROOT);
    }

    private static void requireType(Map<String, Object> node, String type) {
        if (!type.equalsIgnoreCase(asText(node.get("type")))) {
            throw unsupported("Expected " + type + " in reference execution path but found " + asText(node.get("type")));
        }
    }

    private static Common.DatabaseException unsupported(String message) {
        return new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, message);
    }

    private record RelationFacts(JoinStrategy joinStrategy, int tableCount, long estimatedRows, boolean correlatedSubquery) {
    }

    private record QueryFacts(JoinStrategy joinStrategy, int tableCount, long estimatedRows,
                              boolean correlatedSubquery, boolean hasOrderBy) {
    }
}
