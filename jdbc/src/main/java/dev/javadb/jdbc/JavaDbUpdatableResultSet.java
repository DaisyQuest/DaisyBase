package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineApi;

import javax.sql.rowset.CachedRowSet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class JavaDbUpdatableResultSet {
    private JavaDbUpdatableResultSet() {
    }

    static ResultSet create(JavaDbStatement statement, CachedRowSet rowSet,
                            JavaDbUpdatableQueries.Descriptor descriptor) throws SQLException {
        State state = new State(statement, rowSet, descriptor);
        state.resyncKeySnapshots();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getConcurrency" -> ResultSet.CONCUR_UPDATABLE;
            case "getType" -> statement.getResultSetType();
            case "updateRow" -> {
                state.updateCurrentRow();
                yield null;
            }
            case "deleteRow" -> {
                state.deleteCurrentRow();
                yield null;
            }
            case "insertRow" -> {
                state.insertCurrentRow();
                yield null;
            }
            case "refreshRow" -> {
                state.refreshCurrentRow();
                yield null;
            }
            case "moveToInsertRow" -> {
                state.insertMode = true;
                yield invoke(method, rowSet, args);
            }
            case "moveToCurrentRow" -> {
                state.insertMode = false;
                yield invoke(method, rowSet, args);
            }
            default -> invoke(method, rowSet, args);
        };
        return (ResultSet) Proxy.newProxyInstance(
                JavaDbUpdatableResultSet.class.getClassLoader(),
                new Class[]{ResultSet.class},
                handler);
    }

    private static Object invoke(Method method, CachedRowSet rowSet, Object[] args) throws Throwable {
        try {
            return method.invoke(rowSet, args);
        } catch (InvocationTargetException invocationTargetException) {
            throw invocationTargetException.getTargetException();
        }
    }

    private static final class State {
        private final JavaDbStatement statement;
        private final CachedRowSet rowSet;
        private final JavaDbUpdatableQueries.Descriptor descriptor;
        private List<Object[]> keySnapshots = List.of();
        private boolean insertMode;

        private State(JavaDbStatement statement, CachedRowSet rowSet, JavaDbUpdatableQueries.Descriptor descriptor) {
            this.statement = statement;
            this.rowSet = rowSet;
            this.descriptor = descriptor;
        }

        private void updateCurrentRow() throws SQLException {
            int rowIndex = requireCurrentRowIndex();
            Object[] keys = keySnapshots.get(rowIndex - 1);
            List<Object> currentValues = currentRowValues();
            String sql = "UPDATE " + descriptor.qualifiedTableName()
                    + " SET " + joinAssignments(currentValues)
                    + " WHERE " + joinWhere(keys);
            EngineApi.BatchResult result = execute(sql);
            if (result.statements().isEmpty() || result.statements().getFirst().updateCount() != 1) {
                throw new SQLException("Updatable result set update affected "
                        + (result.statements().isEmpty() ? 0 : result.statements().getFirst().updateCount()) + " rows");
            }
            rowSet.updateRow();
            resyncKeySnapshots();
        }

        private void deleteCurrentRow() throws SQLException {
            int rowIndex = requireCurrentRowIndex();
            Object[] keys = keySnapshots.get(rowIndex - 1);
            String sql = "DELETE FROM " + descriptor.qualifiedTableName() + " WHERE " + joinWhere(keys);
            EngineApi.BatchResult result = execute(sql);
            if (result.statements().isEmpty() || result.statements().getFirst().updateCount() != 1) {
                throw new SQLException("Updatable result set delete affected "
                        + (result.statements().isEmpty() ? 0 : result.statements().getFirst().updateCount()) + " rows");
            }
            rowSet.deleteRow();
            resyncKeySnapshots();
        }

        private void insertCurrentRow() throws SQLException {
            List<Object> insertValues = currentRowValues();
            String sql = "INSERT INTO " + descriptor.qualifiedTableName()
                    + " (" + String.join(", ", descriptor.projectedColumns()) + ") VALUES ("
                    + joinLiterals(insertValues) + ")";
            EngineApi.BatchResult result = execute(sql);
            EngineApi.StatementResult statementResult = result.statements().isEmpty() ? null : result.statements().getFirst();
            if (statementResult == null || statementResult.updateCount() != 1) {
                throw new SQLException("Updatable result set insert affected "
                        + (statementResult == null ? 0 : statementResult.updateCount()) + " rows");
            }
            applyGeneratedKeys(statementResult.generatedKeys(), insertValues);
            for (int index = 0; index < insertValues.size(); index++) {
                rowSet.updateObject(index + 1, insertValues.get(index));
            }
            rowSet.insertRow();
            insertMode = false;
            rowSet.moveToCurrentRow();
            resyncKeySnapshots();
        }

        private void refreshCurrentRow() throws SQLException {
            int rowIndex = requireCurrentRowIndex();
            Object[] keys = keySnapshots.get(rowIndex - 1);
            String sql = "SELECT " + String.join(", ", descriptor.projectedColumns())
                    + " FROM " + descriptor.qualifiedTableName()
                    + " WHERE " + joinWhere(keys);
            EngineApi.BatchResult result = execute(sql);
            if (result.statements().isEmpty() || result.statements().getFirst().batch().rows().isEmpty()) {
                throw new SQLException("Updatable result set refresh could not locate the current row");
            }
            Common.ResultRow refreshed = result.statements().getFirst().batch().rows().getFirst();
            for (int index = 0; index < refreshed.values().size(); index++) {
                rowSet.updateObject(index + 1, jdbcValue(refreshed.get(index)));
            }
            rowSet.updateRow();
            resyncKeySnapshots();
        }

        private int requireCurrentRowIndex() throws SQLException {
            int row = rowSet.getRow();
            if (row <= 0) {
                throw new SQLException("Cursor is not positioned on a current row");
            }
            return row;
        }

        private List<Object> currentRowValues() throws SQLException {
            List<Object> values = new ArrayList<>(descriptor.projectedColumns().size());
            for (int index = 1; index <= descriptor.projectedColumns().size(); index++) {
                values.add(rowSet.getObject(index));
            }
            return values;
        }

        private void applyGeneratedKeys(Common.TupleBatch generatedKeys, List<Object> values) {
            if (generatedKeys == null || generatedKeys.columns().isEmpty() || generatedKeys.rows().isEmpty()) {
                return;
            }
            Common.ResultRow row = generatedKeys.rows().getFirst();
            for (int index = 0; index < generatedKeys.columns().size(); index++) {
                String generatedColumn = generatedKeys.columns().get(index).name().toLowerCase(java.util.Locale.ROOT);
                int projectedIndex = descriptor.projectedColumns().indexOf(generatedColumn);
                if (projectedIndex >= 0 && values.get(projectedIndex) == null) {
                    values.set(projectedIndex, jdbcValue(row.get(index)));
                }
            }
        }

        private EngineApi.BatchResult execute(String sql) throws SQLException {
            return statement.connection.executeSql(sql, statement.connection.nextExecutionId(), statement.timeoutMillis());
        }

        private String joinAssignments(List<Object> values) throws SQLException {
            List<String> assignments = new ArrayList<>(descriptor.projectedColumns().size());
            for (int index = 0; index < descriptor.projectedColumns().size(); index++) {
                assignments.add(descriptor.projectedColumns().get(index) + " = "
                        + sqlLiteral(values.get(index), descriptor.resultColumns().get(index)));
            }
            return String.join(", ", assignments);
        }

        private String joinWhere(Object[] keys) throws SQLException {
            List<String> clauses = new ArrayList<>(descriptor.keyColumnIndexes().size());
            for (int index = 0; index < descriptor.keyColumnIndexes().size(); index++) {
                int columnIndex = descriptor.keyColumnIndexes().get(index);
                clauses.add(descriptor.projectedColumns().get(columnIndex - 1) + " = "
                        + sqlLiteral(keys[index], descriptor.resultColumns().get(columnIndex - 1)));
            }
            return String.join(" AND ", clauses);
        }

        private String joinLiterals(List<Object> values) throws SQLException {
            List<String> literals = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                literals.add(sqlLiteral(values.get(index), descriptor.resultColumns().get(index)));
            }
            return String.join(", ", literals);
        }

        private String sqlLiteral(Object value, Common.ResultColumn column) throws SQLException {
            Integer jdbcType = switch (column.type()) {
                case INTEGER -> Types.INTEGER;
                case BIGINT -> Types.BIGINT;
                case BOOLEAN -> Types.BOOLEAN;
                case TEXT -> Types.VARCHAR;
                case DECIMAL -> Types.DECIMAL;
                case DATE -> Types.DATE;
                case TIME -> Types.TIME;
                case TIMESTAMP -> Types.TIMESTAMP;
            };
            return JavaDbPreparedSql.BoundParameter.of(JavaDbJdbcObjects.normalizeParameterValue(value), jdbcType).sqlLiteral();
        }

        private void resyncKeySnapshots() throws SQLException {
            int currentRow = rowSet.getRow();
            boolean wasInsertMode = insertMode;
            if (wasInsertMode) {
                rowSet.moveToCurrentRow();
            }
            List<Object[]> snapshots = new ArrayList<>();
            rowSet.beforeFirst();
            while (rowSet.next()) {
                Object[] keys = new Object[descriptor.keyColumnIndexes().size()];
                for (int index = 0; index < descriptor.keyColumnIndexes().size(); index++) {
                    keys[index] = rowSet.getObject(descriptor.keyColumnIndexes().get(index));
                }
                snapshots.add(keys);
            }
            keySnapshots = List.copyOf(snapshots);
            if (currentRow > 0 && currentRow <= snapshots.size()) {
                rowSet.absolute(currentRow);
            } else {
                rowSet.beforeFirst();
            }
            if (wasInsertMode) {
                rowSet.moveToInsertRow();
                insertMode = true;
            }
        }

        private Object jdbcValue(Common.Value value) {
            if (value == null || value.isNull()) {
                return null;
            }
            return switch (value.type()) {
                case INTEGER -> value.asInt();
                case BIGINT -> value.asLong();
                case BOOLEAN -> value.asBoolean();
                case TEXT -> value.asText();
                case DECIMAL -> value.asDecimal();
                case DATE -> java.sql.Date.valueOf(value.asDate());
                case TIME -> java.sql.Time.valueOf(value.asTime());
                case TIMESTAMP -> Timestamp.valueOf(value.asTimestamp());
            };
        }
    }
}
