package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineApi;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class JavaDbResultSets {
    private JavaDbResultSets() {
    }

    static ResultSet fromStatementResult(EngineApi.StatementResult statementResult, long maxRows,
                                         JavaDbStatement statement,
                                         JavaDbUpdatableQueries.Descriptor updatableDescriptor) throws SQLException {
        if (statementResult.commandTag().equalsIgnoreCase("EXPLAIN")) {
            return singleColumn(List.of(new Common.ResultRow(List.of(Common.Value.text(statementResult.explainPlan())))),
                    "PLAN", Types.VARCHAR, "TEXT", maxRows);
        }
        if (statementResult.batch().columns().isEmpty()) {
            return null;
        }
        CachedRowSet rowSet = fromTupleBatch(statementResult.batch(), maxRows);
        if (updatableDescriptor != null) {
            return JavaDbUpdatableResultSet.create(statement, rowSet, updatableDescriptor);
        }
        if (statement.getResultSetConcurrency() == ResultSet.CONCUR_UPDATABLE) {
            return readOnlyResultSet(rowSet, statement.getResultSetType());
        }
        return rowSet;
    }

    static CachedRowSet fromTupleBatch(Common.TupleBatch batch, long maxRows) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        rowSet.setMetaData(buildMetadata(batch.columns()));
        long emitted = 0;
        for (int rowIndex = batch.rows().size() - 1; rowIndex >= 0; rowIndex--) {
            Common.ResultRow row = batch.rows().get(rowIndex);
            if (maxRows > 0 && emitted >= maxRows) {
                break;
            }
            rowSet.moveToInsertRow();
            for (int index = 0; index < row.values().size(); index++) {
                updateRowSet(rowSet, index + 1, row.get(index));
            }
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
            emitted++;
        }
        rowSet.beforeFirst();
        return rowSet;
    }

    static CachedRowSet empty(String... columns) throws SQLException {
        List<Common.ResultColumn> resultColumns = new ArrayList<>();
        for (String column : columns) {
            resultColumns.add(new Common.ResultColumn(column, Common.DataType.TEXT));
        }
        return fromTupleBatch(new Common.TupleBatch(resultColumns, List.of()), 0);
    }

    static ResultSetMetaData metaData(List<Common.ResultColumn> columns) throws SQLException {
        return buildMetadata(columns);
    }

    private static CachedRowSet singleColumn(List<Common.ResultRow> rows, String name, int jdbcType,
                                             String typeName, long maxRows) throws SQLException {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(1);
        metadata.setColumnName(1, name);
        metadata.setColumnLabel(1, name);
        metadata.setColumnType(1, jdbcType);
        metadata.setColumnTypeName(1, typeName);
        metadata.setPrecision(1, Types.DECIMAL == jdbcType ? 38 : 0);
        metadata.setScale(1, Types.DECIMAL == jdbcType ? 18 : 0);
        rowSet.setMetaData(metadata);
        long emitted = 0;
        for (int rowIndex = rows.size() - 1; rowIndex >= 0; rowIndex--) {
            Common.ResultRow row = rows.get(rowIndex);
            if (maxRows > 0 && emitted >= maxRows) {
                break;
            }
            rowSet.moveToInsertRow();
            updateRowSet(rowSet, 1, row.get(0));
            rowSet.insertRow();
            rowSet.moveToCurrentRow();
            emitted++;
        }
        rowSet.beforeFirst();
        return rowSet;
    }

    private static RowSetMetaDataImpl buildMetadata(List<Common.ResultColumn> columns) throws SQLException {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            Common.ResultColumn column = columns.get(index);
            int jdbcIndex = index + 1;
            metadata.setColumnName(jdbcIndex, column.name());
            metadata.setColumnLabel(jdbcIndex, column.name());
            metadata.setColumnType(jdbcIndex, jdbcType(column.type()));
            metadata.setColumnTypeName(jdbcIndex, typeName(column.type()));
            metadata.setPrecision(jdbcIndex, column.precision() == null ? column.type().defaultPrecision() : column.precision());
            metadata.setScale(jdbcIndex, column.scale() == null ? column.type().defaultScale() : column.scale());
            metadata.setNullable(jdbcIndex, ResultSetMetaData.columnNullableUnknown);
        }
        return metadata;
    }

    private static void updateRowSet(CachedRowSet rowSet, int columnIndex, Common.Value value) throws SQLException {
        if (value == null || value.isNull()) {
            rowSet.updateNull(columnIndex);
            return;
        }
        switch (value.type()) {
            case INTEGER -> rowSet.updateInt(columnIndex, value.asInt());
            case BIGINT -> rowSet.updateLong(columnIndex, value.asLong());
            case BOOLEAN -> rowSet.updateBoolean(columnIndex, value.asBoolean());
            case TEXT -> rowSet.updateString(columnIndex, value.asText());
            case DECIMAL -> rowSet.updateBigDecimal(columnIndex, value.asDecimal());
            case DATE -> rowSet.updateDate(columnIndex, java.sql.Date.valueOf(value.asDate()));
            case TIME -> rowSet.updateTime(columnIndex, java.sql.Time.valueOf(value.asTime()));
            case TIMESTAMP -> rowSet.updateTimestamp(columnIndex, java.sql.Timestamp.valueOf(value.asTimestamp()));
        }
    }

    private static int jdbcType(Common.DataType type) {
        return switch (type) {
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case BOOLEAN -> Types.BOOLEAN;
            case TEXT -> Types.VARCHAR;
            case DECIMAL -> Types.DECIMAL;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
        };
    }

    private static String typeName(Common.DataType type) {
        return switch (type) {
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case BOOLEAN -> "BOOLEAN";
            case TEXT -> "TEXT";
            case DECIMAL -> "DECIMAL";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "TIMESTAMP";
        };
    }

    private static ResultSet readOnlyResultSet(CachedRowSet rowSet, int resultSetType) {
        return (ResultSet) Proxy.newProxyInstance(
                JavaDbResultSets.class.getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;
                    case "getType" -> resultSetType;
                    default -> {
                        try {
                            yield method.invoke(rowSet, args);
                        } catch (InvocationTargetException invocationTargetException) {
                            throw invocationTargetException.getTargetException();
                        }
                    }
                });
    }
}
