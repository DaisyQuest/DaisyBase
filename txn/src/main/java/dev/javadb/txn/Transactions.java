package dev.javadb.txn;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class Transactions {
    private Transactions() {
    }

    public static final class TableDelta {
        private final LinkedHashMap<Common.RowId, List<Common.Value>> inserts;
        private final LinkedHashMap<Common.RowId, List<Common.Value>> updates;
        private final LinkedHashSet<Common.RowId> deletes;

        public TableDelta() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>());
        }

        private TableDelta(LinkedHashMap<Common.RowId, List<Common.Value>> inserts,
                           LinkedHashMap<Common.RowId, List<Common.Value>> updates,
                           LinkedHashSet<Common.RowId> deletes) {
            this.inserts = inserts;
            this.updates = updates;
            this.deletes = deletes;
        }

        public LinkedHashMap<Common.RowId, List<Common.Value>> inserts() {
            return inserts;
        }

        public LinkedHashMap<Common.RowId, List<Common.Value>> updates() {
            return updates;
        }

        public LinkedHashSet<Common.RowId> deletes() {
            return deletes;
        }

        public TableDelta copy() {
            LinkedHashMap<Common.RowId, List<Common.Value>> copiedInserts = new LinkedHashMap<>();
            inserts.forEach((rowId, values) -> copiedInserts.put(rowId, List.copyOf(values)));
            LinkedHashMap<Common.RowId, List<Common.Value>> copiedUpdates = new LinkedHashMap<>();
            updates.forEach((rowId, values) -> copiedUpdates.put(rowId, List.copyOf(values)));
            return new TableDelta(copiedInserts, copiedUpdates, new LinkedHashSet<>(deletes));
        }
    }

    public record SavepointState(String name, Catalog.CatalogSnapshot catalogSnapshot,
                                 List<Catalog.CatalogChange> catalogChanges,
                                 Map<Common.ObjectId, TableDelta> tableDeltas) {
    }

    public static final class TransactionState {
        private final Common.TransactionId transactionId;
        private final Common.IsolationLevel isolationLevel;
        private final long snapshotSequence;
        private final AtomicLong tempRowIds;
        private final Catalog.CatalogSnapshot initialCatalogSnapshot;
        private final Deque<SavepointState> savepoints = new ArrayDeque<>();
        private Catalog.CatalogSnapshot catalogSnapshot;
        private final List<Catalog.CatalogChange> catalogChanges = new ArrayList<>();
        private final LinkedHashMap<Common.ObjectId, TableDelta> tableDeltas = new LinkedHashMap<>();

        public TransactionState(Common.TransactionId transactionId, Common.IsolationLevel isolationLevel,
                                long snapshotSequence, Catalog.CatalogSnapshot catalogSnapshot,
                                AtomicLong tempRowIds) {
            this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
            this.isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel");
            this.snapshotSequence = snapshotSequence;
            this.initialCatalogSnapshot = Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
            this.catalogSnapshot = Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
            this.tempRowIds = tempRowIds;
        }

        public Common.TransactionId transactionId() {
            return transactionId;
        }

        public Common.IsolationLevel isolationLevel() {
            return isolationLevel;
        }

        public long snapshotSequence() {
            return snapshotSequence;
        }

        public Catalog.CatalogSnapshot catalogSnapshot() {
            return catalogSnapshot;
        }

        public List<Catalog.CatalogChange> catalogChanges() {
            return List.copyOf(catalogChanges);
        }

        public Map<Common.ObjectId, TableDelta> tableDeltas() {
            LinkedHashMap<Common.ObjectId, TableDelta> copy = new LinkedHashMap<>();
            tableDeltas.forEach((tableId, delta) -> copy.put(tableId, delta.copy()));
            return copy;
        }

        public TableDelta currentDelta(Common.ObjectId tableId) {
            return tableDeltas.get(tableId);
        }

        public void stageCatalogChange(Catalog.CatalogChange change) {
            catalogSnapshot = Catalog.applyChanges(catalogSnapshot, List.of(change));
            catalogChanges.add(change);
        }

        public Common.RowId stageInsert(Common.ObjectId tableId, List<Common.Value> values) {
            TableDelta delta = tableDeltas.computeIfAbsent(tableId, ignored -> new TableDelta());
            Common.RowId tempRowId = new Common.RowId(tempRowIds.getAndDecrement());
            delta.inserts().put(tempRowId, List.copyOf(values));
            return tempRowId;
        }

        public void stageUpdate(Common.ObjectId tableId, Common.RowId rowId, List<Common.Value> values) {
            TableDelta delta = tableDeltas.computeIfAbsent(tableId, ignored -> new TableDelta());
            if (rowId.temporary()) {
                if (!delta.inserts().containsKey(rowId)) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT, "Unknown temporary row " + rowId.value());
                }
                delta.inserts().put(rowId, List.copyOf(values));
                return;
            }
            if (delta.deletes().contains(rowId)) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT, "Cannot update deleted row " + rowId.value());
            }
            delta.updates().put(rowId, List.copyOf(values));
        }

        public void stageDelete(Common.ObjectId tableId, Common.RowId rowId) {
            TableDelta delta = tableDeltas.computeIfAbsent(tableId, ignored -> new TableDelta());
            if (rowId.temporary()) {
                delta.inserts().remove(rowId);
                return;
            }
            delta.updates().remove(rowId);
            delta.deletes().add(rowId);
        }

        public void savepoint(String name) {
            LinkedHashMap<Common.ObjectId, TableDelta> deltaCopy = new LinkedHashMap<>();
            tableDeltas.forEach((tableId, delta) -> deltaCopy.put(tableId, delta.copy()));
            savepoints.push(new SavepointState(name, catalogSnapshot, List.copyOf(catalogChanges), deltaCopy));
        }

        public void rollbackToSavepoint(String name) {
            SavepointState target = null;
            while (!savepoints.isEmpty()) {
                SavepointState candidate = savepoints.pop();
                if (candidate.name().equalsIgnoreCase(name)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Unknown savepoint " + name);
            }
            restore(target);
        }

        public void rollbackAll() {
            catalogSnapshot = initialCatalogSnapshot;
            catalogChanges.clear();
            tableDeltas.clear();
            savepoints.clear();
        }

        private void restore(SavepointState state) {
            catalogSnapshot = state.catalogSnapshot();
            catalogChanges.clear();
            catalogChanges.addAll(state.catalogChanges());
            tableDeltas.clear();
            state.tableDeltas().forEach((tableId, delta) -> tableDeltas.put(tableId, delta.copy()));
        }
    }

    public static final class TransactionManager {
        private final AtomicLong nextTransactionId = new AtomicLong(1);
        private final AtomicLong lastCommitSequence;
        private final AtomicLong tempRowIds = new AtomicLong(-1);

        public TransactionManager(long initialCommitSequence) {
            this.lastCommitSequence = new AtomicLong(initialCommitSequence);
        }

        public TransactionState begin(Common.IsolationLevel isolationLevel, Catalog.CatalogSnapshot snapshot) {
            long snapshotSequence = switch (isolationLevel) {
                case READ_COMMITTED -> lastCommitSequence.get();
                case REPEATABLE_READ, SERIALIZABLE -> lastCommitSequence.get();
            };
            return new TransactionState(new Common.TransactionId(nextTransactionId.getAndIncrement()), isolationLevel,
                    snapshotSequence, snapshot, tempRowIds);
        }

        public long currentCommitSequence() {
            return lastCommitSequence.get();
        }

        public long nextCommitSequence() {
            return lastCommitSequence.incrementAndGet();
        }

        public void setLastCommitSequence(long value) {
            lastCommitSequence.set(value);
        }
    }
}
