package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineIntrospection;
import dev.javadb.sql.SqlFrontend;

import javax.sql.rowset.CachedRowSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class JavaDbUpdatableQueries {
    private JavaDbUpdatableQueries() {
    }

    static Descriptor describe(String sql, Common.TupleBatch batch, JavaDbConnection connection) throws SQLException {
        if (sql == null || sql.isBlank() || batch.columns().isEmpty()) {
            return null;
        }
        SqlFrontend.Statement statement;
        try {
            SqlFrontend.StatementBatch parsed = SqlFrontend.parseBatch(sql);
            if (parsed.statements().size() != 1) {
                return null;
            }
            statement = parsed.statements().getFirst();
        } catch (RuntimeException runtimeException) {
            return null;
        }
        if (!(statement instanceof SqlFrontend.SelectStatement select)) {
            return null;
        }
        if (!select.groupBy().isEmpty() || select.having() != null) {
            return null;
        }
        List<String> projectedColumns = projectedColumns(select, batch);
        if (projectedColumns == null || projectedColumns.size() != batch.columns().size()) {
            return null;
        }
        String schemaName = select.from().schemaOrDefault().toLowerCase(Locale.ROOT);
        String tableName = select.from().name().toLowerCase(Locale.ROOT);
        List<String> keyColumns = primaryKeyColumns(connection, schemaName, tableName);
        if (keyColumns.isEmpty()) {
            return null;
        }
        List<Integer> keyColumnIndexes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String keyColumn : keyColumns) {
            int index = projectedColumns.indexOf(keyColumn);
            if (index < 0 || !seen.add(keyColumn)) {
                return null;
            }
            keyColumnIndexes.add(index + 1);
        }
        return new Descriptor(schemaName, tableName, projectedColumns, keyColumnIndexes, batch.columns());
    }

    private static List<String> projectedColumns(SqlFrontend.SelectStatement select, Common.TupleBatch batch) {
        if (select.selectItems().size() == 1 && select.selectItems().getFirst().expression() instanceof SqlFrontend.StarExpression) {
            return batch.columns().stream()
                    .map(column -> column.name().toLowerCase(Locale.ROOT))
                    .toList();
        }
        List<String> columns = new ArrayList<>(select.selectItems().size());
        for (SqlFrontend.SelectItem item : select.selectItems()) {
            if (!(item.expression() instanceof SqlFrontend.IdentifierExpression identifier)) {
                return null;
            }
            if (identifier.qualifiedName().schema() != null) {
                return null;
            }
            columns.add(identifier.qualifiedName().name().toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static List<String> primaryKeyColumns(JavaDbConnection connection, String schemaName, String tableName) throws SQLException {
        try (CachedRowSet primaryKeys = connection.metadata(EngineIntrospection.MetadataQuery.PRIMARY_KEYS,
                List.of(schemaName, tableName))) {
            List<String> keys = new ArrayList<>();
            while (primaryKeys.next()) {
                keys.add(primaryKeys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            return keys;
        }
    }

    record Descriptor(String schemaName, String tableName, List<String> projectedColumns,
                      List<Integer> keyColumnIndexes, List<Common.ResultColumn> resultColumns) {
        Descriptor {
            projectedColumns = List.copyOf(projectedColumns);
            keyColumnIndexes = List.copyOf(keyColumnIndexes);
            resultColumns = List.copyOf(resultColumns);
        }

        String qualifiedTableName() {
            return schemaName + "." + tableName;
        }
    }
}
