package dev.javadb.execution;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.index.Indexes;
import dev.javadb.planner.Planner;
import dev.javadb.planner.ReferenceQueries;
import dev.javadb.sql.SqlFrontend;
import dev.javadb.storage.Storage;
import dev.javadb.txn.Transactions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.math.MathContext;

public final class Execution {
    private Execution() {
    }

    public interface SequenceAllocator {
        Common.Value nextValue(Catalog.SequenceDefinition sequence);
        Common.Value nextIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column);
        void observeIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column, Common.Value value);
    }

    public record ExecutionContext(Catalog.CatalogSnapshot catalogSnapshot,
                                   Storage.StorageSnapshot committedStorage,
                                   Indexes.IndexSnapshot indexSnapshot,
                                   long statementSnapshotSequence,
                                   Transactions.TransactionState transactionState,
                                   SequenceAllocator sequenceAllocator,
                                   Common.ExecutionControl executionControl) {
        public ExecutionContext {
            executionControl = executionControl == null ? Common.ExecutionControl.none() : executionControl;
        }

        public void checkCancelled() {
            executionControl.check();
        }
    }

    public record ExecutionResult(String commandTag, long updateCount, Common.TupleBatch tupleBatch,
                                  Common.TupleBatch generatedKeys) {
        public static ExecutionResult update(String tag, long updateCount) {
            return new ExecutionResult(tag, updateCount, Common.TupleBatch.empty(), Common.TupleBatch.empty());
        }

        public static ExecutionResult update(String tag, long updateCount, Common.TupleBatch generatedKeys) {
            return new ExecutionResult(tag, updateCount, Common.TupleBatch.empty(), generatedKeys);
        }

        public static ExecutionResult query(Common.TupleBatch tupleBatch) {
            return new ExecutionResult("SELECT", tupleBatch.rows().size(), tupleBatch, Common.TupleBatch.empty());
        }
    }

    public static final class Executor {
        public ExecutionResult execute(Planner.PhysicalPlan plan, ExecutionContext context) {
            return switch (plan) {
                case Planner.CatalogPlan catalogPlan -> executeCatalog(catalogPlan, context);
                case Planner.InsertPlan insertPlan -> executeInsert(insertPlan.statement(), context);
                case Planner.TableScanPlan scanPlan -> executeSelect(scanPlan.statement(), null, context);
                case Planner.IndexLookupPlan lookupPlan -> executeSelect(lookupPlan.statement(), lookupPlan, context);
                case Planner.EmptyResultPlan emptyResultPlan -> executeEmptySelect(emptyResultPlan.statement());
                case Planner.UpdatePlan updatePlan -> executeUpdate(updatePlan.statement(), context);
                case Planner.DeletePlan deletePlan -> executeDelete(deletePlan.statement(), context);
                case ReferenceQueries.ReferenceQueryPlan referenceQueryPlan -> ReferenceQueryExecution.execute(referenceQueryPlan, context);
            };
        }

        public void validateTransactionAgainstCurrentState(Catalog.CatalogSnapshot catalogSnapshot,
                                                           Storage.StorageSnapshot storageSnapshot,
                                                           Transactions.TransactionState transactionState,
                                                           long snapshotSequence) {
            for (Map.Entry<Common.ObjectId, Transactions.TableDelta> entry : transactionState.tableDeltas().entrySet()) {
                Catalog.TableDefinition table = catalogSnapshot.tablesById().get(entry.getKey());
                if (table == null) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                            "Table no longer exists: " + entry.getKey().value());
                }
                List<Storage.VisibleRow> visibleRows = Storage.visibleRows(storageSnapshot, table.id(), snapshotSequence, entry.getValue());
                for (Map.Entry<Common.RowId, List<Common.Value>> insert : entry.getValue().inserts().entrySet()) {
                    validateRow(table, insert.getValue(), visibleRows, insert.getKey());
                }
                for (Map.Entry<Common.RowId, List<Common.Value>> update : entry.getValue().updates().entrySet()) {
                    validateExistingRowUnchanged(storageSnapshot, table.id(), update.getKey(), transactionState.snapshotSequence(), snapshotSequence);
                    validateRow(table, update.getValue(), visibleRows, update.getKey());
                }
                for (Common.RowId delete : entry.getValue().deletes()) {
                    validateExistingRowUnchanged(storageSnapshot, table.id(), delete, transactionState.snapshotSequence(), snapshotSequence);
                }
            }
        }

        private ExecutionResult executeCatalog(Planner.CatalogPlan plan, ExecutionContext context) {
            context.transactionState().stageCatalogChange(plan.statement().change());
            return ExecutionResult.update("DDL", 0);
        }

        private ExecutionResult executeInsert(Planner.BoundInsert statement, ExecutionContext context) {
            long updated = 0;
            List<Catalog.ColumnDefinition> identityColumns = statement.table().columns().stream()
                    .filter(column -> column.identityDefinition() != null)
                    .toList();
            List<Common.ResultColumn> generatedKeyColumns = identityColumns.stream()
                    .map(column -> new Common.ResultColumn(column.name(), column.type()))
                    .toList();
            List<Common.ResultRow> generatedKeyRows = new ArrayList<>();
            for (List<Planner.BoundExpression> rowExpressions : statement.rows()) {
                context.checkCancelled();
                List<Common.Value> row = new ArrayList<>();
                for (Catalog.ColumnDefinition column : statement.table().columns()) {
                    row.add(Common.Value.nullValue(column.type()));
                }
                Set<Integer> providedOrdinals = new HashSet<>();
                for (int index = 0; index < statement.targetOrdinals().size(); index++) {
                    int ordinal = statement.targetOrdinals().get(index);
                    Catalog.ColumnDefinition column = statement.table().columns().get(ordinal);
                    providedOrdinals.add(ordinal);
                    row.set(ordinal, Common.Values.coerce(
                            evaluate(statement.table(), rowExpressions.get(index), null, context.sequenceAllocator()),
                            column.type(), column.precision(), column.scale()));
                }
                List<Common.Value> generatedValues = new ArrayList<>();
                boolean generatedAny = false;
                for (Catalog.ColumnDefinition column : identityColumns) {
                    Common.Value currentValue = row.get(column.ordinal());
                    if (column.identityDefinition().generation() == Catalog.IdentityGeneration.ALWAYS) {
                        if (providedOrdinals.contains(column.ordinal()) && currentValue != null && !currentValue.isNull()) {
                            throw new Common.DatabaseException(Common.ErrorCode.CONSTRAINT_VIOLATION,
                                    "Identity column " + column.name() + " does not accept explicit values");
                        }
                        Common.Value generated = Common.Values.coerce(
                                context.sequenceAllocator().nextIdentityValue(statement.table(), column),
                                column.type(), column.precision(), column.scale());
                        row.set(column.ordinal(), generated);
                        generatedValues.add(generated);
                        generatedAny = true;
                        continue;
                    }
                    if (!providedOrdinals.contains(column.ordinal()) || currentValue == null || currentValue.isNull()) {
                        Common.Value generated = Common.Values.coerce(
                                context.sequenceAllocator().nextIdentityValue(statement.table(), column),
                                column.type(), column.precision(), column.scale());
                        row.set(column.ordinal(), generated);
                        generatedValues.add(generated);
                        generatedAny = true;
                    } else {
                        context.sequenceAllocator().observeIdentityValue(statement.table(), column, currentValue);
                        generatedValues.add(Common.Value.nullValue(column.type()));
                    }
                }
                Common.RowId rowId = context.transactionState().stageInsert(statement.table().id(), row);
                List<Storage.VisibleRow> visibleRows = Storage.visibleRows(context.committedStorage(), statement.table().id(),
                        context.statementSnapshotSequence(), context.transactionState().currentDelta(statement.table().id()));
                validateRow(statement.table(), row, visibleRows, rowId);
                if (generatedAny) {
                    generatedKeyRows.add(new Common.ResultRow(generatedValues));
                }
                updated++;
            }
            Common.TupleBatch generatedKeys = generatedKeyColumns.isEmpty()
                    ? Common.TupleBatch.empty()
                    : new Common.TupleBatch(generatedKeyColumns, generatedKeyRows);
            return ExecutionResult.update("INSERT", updated, generatedKeys);
        }

        private ExecutionResult executeSelect(Planner.BoundSelect statement, Planner.IndexLookupPlan lookupPlan, ExecutionContext context) {
            List<Storage.VisibleRow> rows = lookupPlan == null
                    ? Storage.visibleRows(context.committedStorage(), statement.table().id(), context.statementSnapshotSequence(),
                    context.transactionState().currentDelta(statement.table().id()))
                    : indexedRows(statement, lookupPlan, context);
            List<RowEnvelope> envelopes = new ArrayList<>();
            for (Storage.VisibleRow row : rows) {
                context.checkCancelled();
                if (statement.filter() != null
                        && !truthy(evaluate(statement.table(), statement.filter(), row.values(), context.sequenceAllocator()))) {
                    continue;
                }
                envelopes.add(new RowEnvelope(row.rowId(), row.values()));
            }
            if (statement.aggregateQuery()) {
                return executeAggregateSelect(statement, envelopes, context);
            }
            if (!statement.orderBy().isEmpty()) {
                envelopes.sort(buildComparator(statement, context.sequenceAllocator()));
            }
            if (statement.limit() != null && envelopes.size() > statement.limit()) {
                envelopes = new ArrayList<>(envelopes.subList(0, statement.limit()));
            }
            List<Common.ResultColumn> columns = statement.items().stream()
                    .map(item -> resultColumn(statement.table(), item))
                    .toList();
            List<Common.ResultRow> resultRows = new ArrayList<>();
            for (RowEnvelope envelope : envelopes) {
                context.checkCancelled();
                List<Common.Value> projected = new ArrayList<>();
                for (Planner.BoundSelectItem item : statement.items()) {
                    projected.add(evaluate(statement.table(), item.expression(), envelope.values(), context.sequenceAllocator()));
                }
                resultRows.add(new Common.ResultRow(projected));
            }
            return ExecutionResult.query(new Common.TupleBatch(columns, resultRows));
        }

        private ExecutionResult executeEmptySelect(Planner.BoundSelect statement) {
            List<Common.ResultColumn> columns = statement.items().stream()
                    .map(item -> resultColumn(statement.table(), item))
                    .toList();
            return ExecutionResult.query(new Common.TupleBatch(columns, List.of()));
        }

        private ExecutionResult executeAggregateSelect(Planner.BoundSelect statement, List<RowEnvelope> rows,
                                                       ExecutionContext context) {
            List<GroupEnvelope> groups = buildGroups(statement, rows, context);
            List<GroupEnvelope> visibleGroups = new ArrayList<>();
            int ordinal = 0;
            for (GroupEnvelope group : groups) {
                context.checkCancelled();
                if (statement.having() != null
                        && !truthy(evaluateAggregate(statement.table(), statement.having(), group.rows(), context.sequenceAllocator()))) {
                    continue;
                }
                visibleGroups.add(new GroupEnvelope(ordinal++, group.rows()));
            }
            if (!statement.orderBy().isEmpty()) {
                visibleGroups.sort(buildAggregateComparator(statement, context.sequenceAllocator()));
            }
            if (statement.limit() != null && visibleGroups.size() > statement.limit()) {
                visibleGroups = new ArrayList<>(visibleGroups.subList(0, statement.limit()));
            }
            List<Common.ResultColumn> columns = statement.items().stream()
                    .map(item -> resultColumn(statement.table(), item))
                    .toList();
            List<Common.ResultRow> resultRows = new ArrayList<>();
            for (GroupEnvelope group : visibleGroups) {
                context.checkCancelled();
                List<Common.Value> projected = new ArrayList<>();
                for (Planner.BoundSelectItem item : statement.items()) {
                    projected.add(evaluateAggregate(statement.table(), item.expression(), group.rows(), context.sequenceAllocator()));
                }
                resultRows.add(new Common.ResultRow(projected));
            }
            return ExecutionResult.query(new Common.TupleBatch(columns, resultRows));
        }

        private ExecutionResult executeUpdate(Planner.BoundUpdate statement, ExecutionContext context) {
            List<Storage.VisibleRow> rows = Storage.visibleRows(context.committedStorage(), statement.table().id(), context.statementSnapshotSequence(),
                    context.transactionState().currentDelta(statement.table().id()));
            long updated = 0;
            for (Storage.VisibleRow row : rows) {
                context.checkCancelled();
                if (statement.filter() != null
                        && !truthy(evaluate(statement.table(), statement.filter(), row.values(), context.sequenceAllocator()))) {
                    continue;
                }
                List<Common.Value> newValues = new ArrayList<>(row.values());
                for (Planner.BoundAssignment assignment : statement.assignments()) {
                    Catalog.ColumnDefinition column = statement.table().columns().get(assignment.ordinal());
                    newValues.set(assignment.ordinal(), Common.Values.coerce(
                            evaluate(statement.table(), assignment.expression(), row.values(), context.sequenceAllocator()),
                            column.type(), column.precision(), column.scale()));
                }
                context.transactionState().stageUpdate(statement.table().id(), row.rowId(), newValues);
                List<Storage.VisibleRow> visibleRows = Storage.visibleRows(context.committedStorage(), statement.table().id(), context.statementSnapshotSequence(),
                        context.transactionState().currentDelta(statement.table().id()));
                validateRow(statement.table(), newValues, visibleRows, row.rowId());
                updated++;
            }
            return ExecutionResult.update("UPDATE", updated);
        }

        private ExecutionResult executeDelete(Planner.BoundDelete statement, ExecutionContext context) {
            List<Storage.VisibleRow> rows = Storage.visibleRows(context.committedStorage(), statement.table().id(), context.statementSnapshotSequence(),
                    context.transactionState().currentDelta(statement.table().id()));
            long deleted = 0;
            for (Storage.VisibleRow row : rows) {
                context.checkCancelled();
                if (statement.filter() != null && !truthy(evaluate(statement.table(), statement.filter(), row.values(), context.sequenceAllocator()))) {
                    continue;
                }
                context.transactionState().stageDelete(statement.table().id(), row.rowId());
                deleted++;
            }
            return ExecutionResult.update("DELETE", deleted);
        }

        private List<Storage.VisibleRow> indexedRows(Planner.BoundSelect statement, Planner.IndexLookupPlan plan, ExecutionContext context) {
            List<Storage.VisibleRow> committedRows = Storage.visibleRows(context.committedStorage(), statement.table().id(), context.statementSnapshotSequence(), null);
            Map<Common.RowId, Storage.VisibleRow> byId = new LinkedHashMap<>();
            committedRows.forEach(row -> byId.put(row.rowId(), row));
            List<Storage.VisibleRow> candidateRows = new ArrayList<>();
            for (Common.RowId rowId : Indexes.lookup(context.indexSnapshot(), plan.index(), List.of(plan.keyValue()))) {
                context.checkCancelled();
                Storage.VisibleRow row = byId.get(rowId);
                if (row != null) {
                    candidateRows.add(row);
                }
            }
            Transactions.TableDelta delta = context.transactionState().currentDelta(statement.table().id());
            if (delta != null) {
                Storage.visibleRows(context.committedStorage(), statement.table().id(), context.statementSnapshotSequence(), delta).stream()
                        .filter(row -> !row.rowId().temporary())
                        .filter(row -> truthy(evaluate(statement.table(), statement.filter(), row.values(), context.sequenceAllocator())))
                        .forEach(candidateRows::add);
                delta.inserts().forEach((rowId, values) -> {
                    if (truthy(evaluate(statement.table(), statement.filter(), values, context.sequenceAllocator()))) {
                        candidateRows.add(new Storage.VisibleRow(rowId, values));
                    }
                });
            }
            return candidateRows;
        }

        private Comparator<RowEnvelope> buildComparator(Planner.BoundSelect statement, SequenceAllocator sequenceAllocator) {
            return (left, right) -> {
                for (Planner.BoundOrderBy orderBy : statement.orderBy()) {
                    Common.Value leftValue = evaluate(statement.table(), orderBy.expression(), left.values(), sequenceAllocator);
                    Common.Value rightValue = evaluate(statement.table(), orderBy.expression(), right.values(), sequenceAllocator);
                    int compare = Common.Values.nullsLastComparator().compare(leftValue, rightValue);
                    if (compare != 0) {
                        return orderBy.ascending() ? compare : -compare;
                    }
                }
                return Long.compare(left.rowId().value(), right.rowId().value());
            };
        }

        private Comparator<GroupEnvelope> buildAggregateComparator(Planner.BoundSelect statement, SequenceAllocator sequenceAllocator) {
            return (left, right) -> {
                for (Planner.BoundOrderBy orderBy : statement.orderBy()) {
                    Common.Value leftValue = evaluateAggregate(statement.table(), orderBy.expression(), left.rows(), sequenceAllocator);
                    Common.Value rightValue = evaluateAggregate(statement.table(), orderBy.expression(), right.rows(), sequenceAllocator);
                    int compare = Common.Values.nullsLastComparator().compare(leftValue, rightValue);
                    if (compare != 0) {
                        return orderBy.ascending() ? compare : -compare;
                    }
                }
                return Integer.compare(left.ordinal(), right.ordinal());
            };
        }

        private List<GroupEnvelope> buildGroups(Planner.BoundSelect statement, List<RowEnvelope> rows,
                                               ExecutionContext context) {
            if (statement.groupBy().isEmpty()) {
                return List.of(new GroupEnvelope(0, new ArrayList<>(rows)));
            }
            Map<GroupKey, List<RowEnvelope>> grouped = new LinkedHashMap<>();
            for (RowEnvelope row : rows) {
                context.checkCancelled();
                List<Common.Value> keyValues = statement.groupBy().stream()
                        .map(expression -> evaluate(statement.table(), expression, row.values(), context.sequenceAllocator()))
                        .toList();
                grouped.computeIfAbsent(new GroupKey(keyValues), ignored -> new ArrayList<>()).add(row);
            }
            List<GroupEnvelope> groups = new ArrayList<>();
            int ordinal = 0;
            for (List<RowEnvelope> groupedRows : grouped.values()) {
                groups.add(new GroupEnvelope(ordinal++, groupedRows));
            }
            return groups;
        }

        private Common.ResultColumn resultColumn(Catalog.TableDefinition table, Planner.BoundSelectItem item) {
            Integer precision = null;
            Integer scale = null;
            if (item.expression() instanceof Planner.BoundColumnRef columnRef) {
                Catalog.ColumnDefinition column = table.columns().get(columnRef.ordinal());
                precision = column.precision();
                scale = column.scale();
            } else if (item.expression() instanceof Planner.BoundAggregate aggregate
                    && aggregate.type() == Common.DataType.DECIMAL) {
                if (aggregate.argument() instanceof Planner.BoundColumnRef columnRef) {
                    Catalog.ColumnDefinition column = table.columns().get(columnRef.ordinal());
                    precision = column.precision();
                    scale = column.scale();
                } else {
                    precision = Common.DataType.DECIMAL.defaultPrecision();
                    scale = Common.DataType.DECIMAL.defaultScale();
                }
            } else if (item.expression().type() == Common.DataType.DECIMAL) {
                precision = Common.DataType.DECIMAL.defaultPrecision();
                scale = Common.DataType.DECIMAL.defaultScale();
            }
            return new Common.ResultColumn(item.outputName(), item.expression().type(), precision, scale);
        }

        private void validateExistingRowUnchanged(Storage.StorageSnapshot storageSnapshot, Common.ObjectId tableId, Common.RowId rowId,
                                                  long transactionSnapshotSequence, long currentSequence) {
            List<Common.Value> startRow = Storage.currentRow(storageSnapshot, tableId, rowId, transactionSnapshotSequence);
            List<Common.Value> currentRow = Storage.currentRow(storageSnapshot, tableId, rowId, currentSequence);
            if (!Objects.equals(startRow, currentRow)) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Concurrent modification detected for row " + rowId.value());
            }
        }

        private void validateRow(Catalog.TableDefinition table, List<Common.Value> rowValues, List<Storage.VisibleRow> visibleRows,
                                 Common.RowId rowIdToIgnore) {
            for (Catalog.ColumnDefinition column : table.columns()) {
                Common.Value value = rowValues.get(column.ordinal());
                if (!column.nullable() && (value == null || value.isNull())) {
                    throw new Common.DatabaseException(Common.ErrorCode.CONSTRAINT_VIOLATION,
                            "Column " + column.name() + " may not be null");
                }
                if (column.checkExpressionSql() != null && !column.checkExpressionSql().isBlank()) {
                    SqlFrontend.Expression expression = SqlFrontend.parseExpressionFragment(column.checkExpressionSql());
                    Common.Value result = evaluateCheckExpression(expression, table, rowValues);
                    if (!truthy(result)) {
                        throw new Common.DatabaseException(Common.ErrorCode.CONSTRAINT_VIOLATION,
                                "CHECK constraint failed for column " + column.name());
                    }
                }
            }
            for (Catalog.ColumnDefinition column : table.columns()) {
                if (!(column.primaryKey() || column.unique())) {
                    continue;
                }
                Common.Value value = rowValues.get(column.ordinal());
                if (value == null || value.isNull()) {
                    continue;
                }
                for (Storage.VisibleRow row : visibleRows) {
                    if (row.rowId().equals(rowIdToIgnore)) {
                        continue;
                    }
                    Common.Value other = row.values().get(column.ordinal());
                    if (Boolean.TRUE.equals(Common.Values.equals3vl(value, other))) {
                        throw new Common.DatabaseException(Common.ErrorCode.CONSTRAINT_VIOLATION,
                                "Unique constraint violated on column " + column.name());
                    }
                }
            }
        }

        private Common.Value evaluate(Catalog.TableDefinition table, Planner.BoundExpression expression, List<Common.Value> rowValues,
                                      SequenceAllocator sequenceAllocator) {
            return switch (expression) {
                case Planner.BoundColumnRef columnRef -> rowValues.get(columnRef.ordinal());
                case Planner.BoundLiteral literal -> literal.value();
                case Planner.BoundAggregate ignored -> throw new Common.DatabaseException(Common.ErrorCode.INTERNAL_ERROR,
                        "Aggregate expressions must be evaluated in grouped execution");
                case Planner.BoundScalarFunction function -> evaluateScalarFunction(table, function, rowValues, sequenceAllocator);
                case Planner.BoundBinary binary -> evaluateBinary(table, binary, rowValues, sequenceAllocator);
                case Planner.BoundNextValue nextValue -> sequenceAllocator.nextValue(nextValue.sequence());
            };
        }

        private Common.Value evaluateAggregate(Catalog.TableDefinition table, Planner.BoundExpression expression, List<RowEnvelope> rows,
                                              SequenceAllocator sequenceAllocator) {
            return switch (expression) {
                case Planner.BoundColumnRef columnRef -> rows.isEmpty()
                        ? Common.Value.nullValue(columnRef.type())
                        : rows.getFirst().values().get(columnRef.ordinal());
                case Planner.BoundLiteral literal -> literal.value();
                case Planner.BoundAggregate aggregate -> evaluateAggregateFunction(table, aggregate, rows, sequenceAllocator);
                case Planner.BoundScalarFunction function -> evaluateAggregateScalarFunction(table, function, rows, sequenceAllocator);
                case Planner.BoundBinary binary -> {
                    Common.Value left = evaluateAggregate(table, binary.left(), rows, sequenceAllocator);
                    Common.Value right = evaluateAggregate(table, binary.right(), rows, sequenceAllocator);
                    yield switch (binary.operator()) {
                        case EQ -> Common.Value.bool(Common.Values.compare(left, right, "="));
                        case NEQ -> Common.Value.bool(Common.Values.compare(left, right, "!="));
                        case LT -> Common.Value.bool(Common.Values.compare(left, right, "<"));
                        case LTE -> Common.Value.bool(Common.Values.compare(left, right, "<="));
                        case GT -> Common.Value.bool(Common.Values.compare(left, right, ">"));
                        case GTE -> Common.Value.bool(Common.Values.compare(left, right, ">="));
                        case AND -> Common.Value.bool(and(left, right));
                        case OR -> Common.Value.bool(or(left, right));
                        case ADD -> add(left, right, binary.type());
                        case SUB -> subtract(left, right, binary.type());
                        case MUL -> multiply(left, right, binary.type());
                        case DIV -> divide(left, right, binary.type());
                    };
                }
                case Planner.BoundNextValue nextValue -> sequenceAllocator.nextValue(nextValue.sequence());
            };
        }

        private Common.Value evaluateScalarFunction(Catalog.TableDefinition table, Planner.BoundScalarFunction function,
                                                    List<Common.Value> rowValues, SequenceAllocator sequenceAllocator) {
            List<Common.Value> arguments = function.arguments().stream()
                    .map(argument -> evaluate(table, argument, rowValues, sequenceAllocator))
                    .toList();
            return applyScalarFunction(function.function(), arguments, function.type());
        }

        private Common.Value evaluateAggregateScalarFunction(Catalog.TableDefinition table, Planner.BoundScalarFunction function,
                                                             List<RowEnvelope> rows, SequenceAllocator sequenceAllocator) {
            List<Common.Value> arguments = function.arguments().stream()
                    .map(argument -> evaluateAggregate(table, argument, rows, sequenceAllocator))
                    .toList();
            return applyScalarFunction(function.function(), arguments, function.type());
        }

        private Common.Value evaluateAggregateFunction(Catalog.TableDefinition table, Planner.BoundAggregate aggregate,
                                                       List<RowEnvelope> rows, SequenceAllocator sequenceAllocator) {
            return switch (aggregate.function()) {
                case COUNT -> {
                    if (aggregate.argument() == null) {
                        yield Common.Value.bigint((long) rows.size());
                    }
                    long matches = 0;
                    for (RowEnvelope row : rows) {
                        Common.Value value = evaluate(table, aggregate.argument(), row.values(), sequenceAllocator);
                        if (value != null && !value.isNull()) {
                            matches++;
                        }
                    }
                    yield Common.Value.bigint(matches);
                }
                case SUM -> {
                    java.math.BigDecimal decimalSum = java.math.BigDecimal.ZERO;
                    long longSum = 0L;
                    boolean sawValue = false;
                    for (RowEnvelope row : rows) {
                        Common.Value value = evaluate(table, aggregate.argument(), row.values(), sequenceAllocator);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        sawValue = true;
                        if (aggregate.type() == Common.DataType.DECIMAL) {
                            decimalSum = decimalSum.add(value.asDecimal());
                        } else {
                            longSum += value.type() == Common.DataType.INTEGER ? value.asInt() : value.asLong();
                        }
                    }
                    if (!sawValue) {
                        yield Common.Value.nullValue(aggregate.type());
                    }
                    if (aggregate.type() == Common.DataType.INTEGER) {
                        yield Common.Value.integer((int) longSum);
                    }
                    if (aggregate.type() == Common.DataType.BIGINT) {
                        yield Common.Value.bigint(longSum);
                    }
                    yield Common.Value.decimal(decimalSum);
                }
                case MIN -> {
                    Common.Value best = null;
                    for (RowEnvelope row : rows) {
                        Common.Value value = evaluate(table, aggregate.argument(), row.values(), sequenceAllocator);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        if (best == null || best.compareTo(value) > 0) {
                            best = value;
                        }
                    }
                    yield best == null ? Common.Value.nullValue(aggregate.type()) : best;
                }
                case MAX -> {
                    Common.Value best = null;
                    for (RowEnvelope row : rows) {
                        Common.Value value = evaluate(table, aggregate.argument(), row.values(), sequenceAllocator);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        if (best == null || best.compareTo(value) < 0) {
                            best = value;
                        }
                    }
                    yield best == null ? Common.Value.nullValue(aggregate.type()) : best;
                }
                case AVG -> {
                    java.math.BigDecimal decimalSum = java.math.BigDecimal.ZERO;
                    long longSum = 0L;
                    long count = 0L;
                    for (RowEnvelope row : rows) {
                        Common.Value value = evaluate(table, aggregate.argument(), row.values(), sequenceAllocator);
                        if (value == null || value.isNull()) {
                            continue;
                        }
                        if (aggregate.type() == Common.DataType.DECIMAL) {
                            decimalSum = decimalSum.add(value.asDecimal());
                        } else {
                            longSum += value.type() == Common.DataType.INTEGER ? value.asInt() : value.asLong();
                        }
                        count++;
                    }
                    if (count == 0) {
                        yield Common.Value.nullValue(aggregate.type());
                    }
                    if (aggregate.type() == Common.DataType.DECIMAL) {
                        yield Common.Value.decimal(decimalSum.divide(java.math.BigDecimal.valueOf(count), MathContext.DECIMAL128));
                    }
                    yield Common.Value.bigint(longSum / count);
                }
            };
        }

        private Common.Value applyScalarFunction(Planner.ScalarFunction function, List<Common.Value> arguments, Common.DataType type) {
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
                    if (type == Common.DataType.INTEGER) {
                        yield Common.Value.integer(Math.abs(value.asInt()));
                    }
                    if (type == Common.DataType.BIGINT) {
                        yield Common.Value.bigint(Math.abs(value.asLong()));
                    }
                    yield Common.Value.decimal(value.asDecimal().abs());
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

        private Common.Value evaluateBinary(Catalog.TableDefinition table, Planner.BoundBinary binary, List<Common.Value> rowValues,
                                            SequenceAllocator sequenceAllocator) {
            Common.Value left = evaluate(table, binary.left(), rowValues, sequenceAllocator);
            Common.Value right = evaluate(table, binary.right(), rowValues, sequenceAllocator);
            return switch (binary.operator()) {
                case EQ -> Common.Value.bool(Common.Values.compare(left, right, "="));
                case NEQ -> Common.Value.bool(Common.Values.compare(left, right, "!="));
                case LT -> Common.Value.bool(Common.Values.compare(left, right, "<"));
                case LTE -> Common.Value.bool(Common.Values.compare(left, right, "<="));
                case GT -> Common.Value.bool(Common.Values.compare(left, right, ">"));
                case GTE -> Common.Value.bool(Common.Values.compare(left, right, ">="));
                case AND -> Common.Value.bool(and(left, right));
                case OR -> Common.Value.bool(or(left, right));
                case ADD -> add(left, right, binary.type());
                case SUB -> subtract(left, right, binary.type());
                case MUL -> multiply(left, right, binary.type());
                case DIV -> divide(left, right, binary.type());
            };
        }

        private Common.Value evaluateCheckExpression(SqlFrontend.Expression expression, Catalog.TableDefinition table, List<Common.Value> rowValues) {
            return switch (expression) {
                case SqlFrontend.IdentifierExpression identifier -> {
                    Catalog.ColumnDefinition column = table.requireColumn(identifier.qualifiedName().name());
                    yield rowValues.get(column.ordinal());
                }
                case SqlFrontend.LiteralExpression literal -> literal.value();
                case SqlFrontend.BinaryExpression binary -> {
                    Common.Value left = evaluateCheckExpression(binary.left(), table, rowValues);
                    Common.Value right = evaluateCheckExpression(binary.right(), table, rowValues);
                    yield switch (binary.operator()) {
                        case EQ -> Common.Value.bool(Common.Values.compare(left, right, "="));
                        case NEQ -> Common.Value.bool(Common.Values.compare(left, right, "!="));
                        case LT -> Common.Value.bool(Common.Values.compare(left, right, "<"));
                        case LTE -> Common.Value.bool(Common.Values.compare(left, right, "<="));
                        case GT -> Common.Value.bool(Common.Values.compare(left, right, ">"));
                        case GTE -> Common.Value.bool(Common.Values.compare(left, right, ">="));
                        case AND -> Common.Value.bool(and(left, right));
                        case OR -> Common.Value.bool(or(left, right));
                        case ADD -> add(left, right, left.type());
                        case SUB -> subtract(left, right, left.type());
                        case MUL -> multiply(left, right, left.type());
                        case DIV -> divide(left, right, left.type());
                    };
                }
                case SqlFrontend.FunctionCallExpression functionCall -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Functions are not supported in CHECK constraints", functionCall.span());
                case SqlFrontend.NextValueExpression nextValue -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "NEXT VALUE FOR is not supported in CHECK constraints", nextValue.span());
                case SqlFrontend.ParameterExpression parameter -> throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Prepared-statement parameters are not valid in CHECK constraints", parameter.span());
                case SqlFrontend.StarExpression star -> throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Star is invalid in CHECK constraints", star.span());
            };
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
                        "ADD is only supported on numeric types");
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
                        "SUB is only supported on numeric types");
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
                        "MUL is only supported on numeric types");
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
                        "DIV is only supported on numeric types");
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

        private boolean truthy(Common.Value value) {
            return value != null && !value.isNull() && Boolean.TRUE.equals(value.asBoolean());
        }

        private record GroupKey(List<Common.Value> values) {
        }

        private record GroupEnvelope(int ordinal, List<RowEnvelope> rows) {
        }

        private record RowEnvelope(Common.RowId rowId, List<Common.Value> values) {
        }
    }
}
