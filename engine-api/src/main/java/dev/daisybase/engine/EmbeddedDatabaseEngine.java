package dev.daisybase.engine;

import dev.daisybase.catalog.Catalog;
import dev.daisybase.common.Common;
import dev.daisybase.execution.Execution;
import dev.daisybase.index.Indexes;
import dev.daisybase.planner.Planner;
import dev.daisybase.sql.SqlFrontend;
import dev.daisybase.storage.Storage;
import dev.daisybase.storage.HeapStorageManager;
import dev.daisybase.txn.Transactions;
import dev.daisybase.wal.Wal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class EmbeddedDatabaseEngine implements EngineApi.DatabaseEngine {
    private final EngineApi.DatabaseConfig config;
    private final Path home;
    private final Path catalogPath;
    private final Path dataDir;
    private final Wal.WalManager walManager;
    private final HeapStorageManager storageManager;
    private final SequenceStateStore sequenceStateStore;
    private final PreparedXaStore preparedXaStore;
    private final Transactions.TransactionManager transactionManager;
    private final Execution.Executor executor = new Execution.Executor();
    private final AtomicLong nextObjectId;
    private final AtomicInteger commitsSinceCheckpoint = new AtomicInteger();
    private final Object commitMonitor = new Object();
    private volatile Catalog.CatalogSnapshot committedCatalog;
    private volatile Storage.StorageSnapshot committedStorage;
    private volatile Indexes.IndexSnapshot committedIndexes;
    private volatile boolean closed;

    private EmbeddedDatabaseEngine(EngineApi.DatabaseConfig config, Catalog.CatalogSnapshot catalogSnapshot,
                                   Storage.StorageSnapshot storageSnapshot, long lastCommitSequence,
                                   long nextObjectIdValue, Wal.WalManager walManager, HeapStorageManager storageManager,
                                   SequenceStateStore sequenceStateStore, PreparedXaStore preparedXaStore) {
        this.config = Objects.requireNonNull(config, "config");
        this.home = config.home();
        this.catalogPath = home.resolve("catalog").resolve("catalog.snapshot");
        this.dataDir = home.resolve("data");
        this.walManager = walManager;
        this.storageManager = storageManager;
        this.sequenceStateStore = sequenceStateStore;
        this.preparedXaStore = preparedXaStore;
        this.transactionManager = new Transactions.TransactionManager(lastCommitSequence);
        this.nextObjectId = new AtomicLong(nextObjectIdValue);
        this.committedCatalog = catalogSnapshot;
        this.committedStorage = storageSnapshot;
        this.committedIndexes = Indexes.rebuild(catalogSnapshot, storageSnapshot, Math.max(1, lastCommitSequence));
    }

    public static EngineApi.DatabaseEngine open(Path home) {
        return open(EngineApi.DatabaseConfig.defaults(home));
    }

    public static EngineApi.DatabaseEngine open(EngineApi.DatabaseConfig config) {
        try {
            Files.createDirectories(config.home());
            Wal.WalManager walManager = new Wal.WalManager(config.home().resolve("wal"));
            HeapStorageManager storageManager = new HeapStorageManager(config.home().resolve("data"));
            SequenceStateStore sequenceStateStore = new SequenceStateStore(config.home());
            PreparedXaStore preparedXaStore = new PreparedXaStore(config.home());
            Path catalogPath = config.home().resolve("catalog").resolve("catalog.snapshot");
            Catalog.CatalogSnapshot catalogSnapshot = Catalog.readSnapshot(catalogPath);
            if (catalogSnapshot == null) {
                catalogSnapshot = Catalog.bootstrap(new Common.ObjectId(1));
            }
            Storage.StorageSnapshot storageSnapshot = storageManager.hasLiveFormat()
                    ? storageManager.loadSnapshot()
                    : Storage.readSnapshots(config.home().resolve("data"));
            boolean bootstrapLiveStorage = !storageManager.hasLiveFormat() && !storageSnapshot.tables().isEmpty();
            long lastCommitSequence = walManager.meta().lastCommitSequence();
            for (Wal.RecoveredTransaction transaction : walManager.recover()) {
                catalogSnapshot = Catalog.applyChanges(catalogSnapshot, transaction.catalogChanges());
                if (!transaction.pageImages().isEmpty()) {
                    storageManager.applyRecoveredPages(transaction.pageImages());
                    storageSnapshot = storageManager.loadSnapshot();
                    bootstrapLiveStorage = false;
                } else if (!transaction.mutationRecords().isEmpty()) {
                    storageSnapshot = Storage.applyRecoveredMutations(storageSnapshot,
                            transaction.mutationRecords().stream()
                                    .map(record -> new Storage.RecoveredMutation(record.kind(), record.tableId(), record.rowId(), record.values()))
                                    .toList(),
                            transaction.commitSequence());
                    bootstrapLiveStorage = true;
                }
                lastCommitSequence = Math.max(lastCommitSequence, transaction.commitSequence());
            }
            if (bootstrapLiveStorage) {
                storageManager.bootstrapFromSnapshot(storageSnapshot);
                storageSnapshot = storageManager.loadSnapshot();
            }
            long nextObjectId = Math.max(walManager.meta().nextObjectId(), Catalog.maxObjectId(catalogSnapshot) + 1);
            sequenceStateStore.ensureDefinitions(catalogSnapshot);
            EmbeddedDatabaseEngine engine = new EmbeddedDatabaseEngine(config, catalogSnapshot, storageSnapshot, lastCommitSequence,
                    nextObjectId, walManager, storageManager, sequenceStateStore, preparedXaStore);
            engine.checkpoint();
            walManager.updateNextObjectId(engine.nextObjectId.get());
            return engine;
        } catch (Exception exception) {
            if (exception instanceof Common.DatabaseException databaseException) {
                throw databaseException;
            }
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to open database engine", exception);
        }
    }

    @Override
    public EngineApi.Session openSession() {
        return openSession("system");
    }

    @Override
    public EngineApi.Session openSession(String principal) {
        ensureOpen();
        return new SessionImpl(principal);
    }

    @Override
    public void checkpoint() {
        synchronized (commitMonitor) {
            ensureOpen();
            Catalog.writeSnapshot(catalogPath, committedCatalog);
            storageManager.flushDirtyPages();
            walManager.checkpoint(transactionManager.currentCommitSequence(), nextObjectId.get());
            commitsSinceCheckpoint.set(0);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        checkpoint();
        storageManager.close();
        closed = true;
    }

    Catalog.CatalogSnapshot catalogSnapshotForIntrospection() {
        return committedCatalog;
    }

    List<EngineApi.XidDescriptor> recoverPreparedXa() {
        return preparedXaStore.recover();
    }

    public String authenticatePrincipal(String user, String password) {
        Catalog.CatalogSnapshot snapshot = committedCatalog;
        if (!snapshot.hasAnyUsers()) {
            return "system";
        }
        String candidate = user == null ? "" : user.strip().toLowerCase();
        return snapshot.authenticate(candidate, password == null ? "" : password) ? candidate : null;
    }

    private void ensureOpen() {
        if (closed) {
            throw new Common.DatabaseException(Common.ErrorCode.INTERNAL_ERROR, "Engine is closed");
        }
    }

    private void prepareXaBranch(EngineApi.XidDescriptor xid, Transactions.TransactionState transactionState) {
        synchronized (commitMonitor) {
            ensureOpen();
            if (preparedXaStore.load(xid) != null) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Prepared XA branch already exists");
            }
            preparedXaStore.save(new PreparedXaStore.PreparedBranch(xid, transactionState.freezeForPrepare()));
        }
    }

    private void commitPreparedXa(EngineApi.XidDescriptor xid) {
        synchronized (commitMonitor) {
            ensureOpen();
            PreparedXaStore.PreparedBranch branch = preparedXaStore.load(xid);
            if (branch == null) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Unknown prepared XA branch");
            }
            Transactions.TransactionState restored = transactionManager.restorePrepared(
                    branch.preparedState(), committedCatalog);
            commitTransaction(restored);
            preparedXaStore.delete(xid);
        }
    }

    private void rollbackPreparedXa(EngineApi.XidDescriptor xid) {
        synchronized (commitMonitor) {
            ensureOpen();
            if (preparedXaStore.load(xid) == null) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Unknown prepared XA branch");
            }
            preparedXaStore.delete(xid);
        }
    }

    private long currentStatementSnapshotSequence(Transactions.TransactionState transactionState) {
        return switch (transactionState.isolationLevel()) {
            case READ_COMMITTED -> transactionManager.currentCommitSequence();
            case REPEATABLE_READ, SERIALIZABLE -> transactionState.snapshotSequence();
        };
    }

    private void commitTransaction(Transactions.TransactionState transactionState) {
        synchronized (commitMonitor) {
            Catalog.CatalogSnapshot currentCatalog = committedCatalog;
            Storage.StorageSnapshot currentStorage = committedStorage;
            Catalog.CatalogSnapshot newCatalog = transactionState.catalogChanges().isEmpty()
                    ? currentCatalog
                    : Catalog.applyChanges(currentCatalog, transactionState.catalogChanges());
            executor.validateTransactionAgainstCurrentState(newCatalog, currentStorage, transactionState,
                    transactionManager.currentCommitSequence());
            long commitSequence = transactionManager.nextCommitSequence();
            Storage.AppliedCommit appliedCommit = Storage.applyCommit(currentStorage, transactionState.tableDeltas(), commitSequence);
            List<Wal.MutationRecord> mutationRecords = toWalMutations(transactionState.tableDeltas(), appliedCommit.insertedRowMappings());
            HeapStorageManager.CommitResult physicalCommit = storageManager.applyCommit(
                    transactionState.tableDeltas(), appliedCommit.insertedRowMappings(), commitSequence);
            try {
                Wal.AppendResult appendResult = walManager.appendCommittedTransaction(transactionState.transactionId(),
                        transactionState.catalogChanges(),
                        mutationRecords,
                        physicalCommit.pageImages(),
                        commitSequence,
                        config.strictDurability());
                storageManager.markCommittedPageLsns(appendResult.pageImages());
                storageManager.flushDirtyPages();
            } catch (RuntimeException exception) {
                storageManager.discardUnflushedChanges(physicalCommit.changedTables());
                throw exception;
            }
            committedCatalog = newCatalog;
            committedStorage = appliedCommit.snapshot();
            committedIndexes = Indexes.rebuild(newCatalog, appliedCommit.snapshot(), commitSequence);
            sequenceStateStore.ensureDefinitions(newCatalog);
            walManager.updateNextObjectId(nextObjectId.get());
            if (commitsSinceCheckpoint.incrementAndGet() >= config.checkpointInterval()) {
                checkpoint();
            }
        }
    }

    private List<Wal.MutationRecord> toWalMutations(Map<Common.ObjectId, Transactions.TableDelta> deltas,
                                                    Map<Common.ObjectId, Map<Common.RowId, Common.RowId>> insertedMappings) {
        List<Wal.MutationRecord> records = new ArrayList<>();
        for (Map.Entry<Common.ObjectId, Transactions.TableDelta> entry : deltas.entrySet()) {
            Common.ObjectId tableId = entry.getKey();
            Transactions.TableDelta delta = entry.getValue();
            Map<Common.RowId, Common.RowId> insertMap = insertedMappings.getOrDefault(tableId, Map.of());
            delta.inserts().forEach((tempRowId, values) -> records.add(new Wal.MutationRecord("INSERT", tableId,
                    insertMap.getOrDefault(tempRowId, tempRowId), values)));
            delta.updates().forEach((rowId, values) -> records.add(new Wal.MutationRecord("UPDATE", tableId, rowId, values)));
            delta.deletes().forEach(rowId -> records.add(new Wal.MutationRecord("DELETE", tableId, rowId, List.of())));
        }
        return records;
    }

    private final class SessionImpl implements EngineApi.Session {
        private final String principal;
        private Transactions.TransactionState activeTransaction;
        private int statementCounter;
        private final Map<Long, PreparedStatementState> preparedStatements = new LinkedHashMap<>();
        private final AtomicLong nextPreparedStatementId = new AtomicLong(1);

        private SessionImpl(String principal) {
            this.principal = principal == null || principal.isBlank() ? "system" : principal.toLowerCase();
        }

        @Override
        public EngineApi.BatchResult execute(String sql) {
            return execute(sql, Common.ExecutionControl.none());
        }

        @Override
        public EngineApi.BatchResult execute(String sql, Common.ExecutionControl executionControl) {
            ensureOpen();
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(sql);
            List<EngineApi.StatementResult> results = new ArrayList<>();
            for (SqlFrontend.Statement statement : batch.statements()) {
                results.add(executeStatement(statement, executionControl == null ? Common.ExecutionControl.none() : executionControl));
            }
            return new EngineApi.BatchResult(results);
        }

        @Override
        public EngineApi.PreparedStatementDescription prepare(String sql) {
            ensureOpen();
            PreparedSqlTemplate template;
            try {
                template = PreparedSqlTemplate.parse(sql);
            } catch (IllegalArgumentException illegalArgumentException) {
                throw new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR, illegalArgumentException.getMessage());
            }
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(sql);
            if (batch.statements().size() != 1) {
                throw new Common.DatabaseException(Common.ErrorCode.PARSE_ERROR,
                        "Prepared statements require exactly one SQL statement");
            }
            SqlFrontend.Statement statement = batch.statements().getFirst();
            Catalog.CatalogSnapshot visibleCatalog = activeTransaction == null ? committedCatalog : activeTransaction.catalogSnapshot();
            authorizeStatement(statement, visibleCatalog);
            Planner.Binder binder = new Planner.Binder(visibleCatalog, () -> -1L);
            List<EngineApi.ParameterDescription> parameterDescriptions = describeParameters(statement, visibleCatalog, binder);
            long statementId = nextPreparedStatementId.getAndIncrement();
            EngineApi.PreparedStatementDescription description = new EngineApi.PreparedStatementDescription(
                    statementId, sql, template.parameterCount(),
                    parameterDescriptions,
                    describeStatement(statement));
            preparedStatements.put(statementId, new PreparedStatementState(template, description));
            return description;
        }

        @Override
        public EngineApi.BatchResult executePrepared(long statementId, List<String> parameterLiterals,
                                                     Common.ExecutionControl executionControl) {
            PreparedStatementState prepared = preparedStatements.get(statementId);
            if (prepared == null) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Unknown prepared statement " + statementId);
            }
            String renderedSql;
            try {
                renderedSql = prepared.template().render(parameterLiterals == null ? List.of() : parameterLiterals);
            } catch (IllegalArgumentException illegalArgumentException) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, illegalArgumentException.getMessage());
            }
            return execute(renderedSql, executionControl);
        }

        @Override
        public void closePrepared(long statementId) {
            preparedStatements.remove(statementId);
        }

        @Override
        public void xaPrepare(EngineApi.XidDescriptor xid) {
            ensureOpen();
            if (activeTransaction == null) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "No active transaction to prepare");
            }
            prepareXaBranch(xid, activeTransaction);
            activeTransaction = null;
        }

        @Override
        public void xaCommit(EngineApi.XidDescriptor xid, boolean onePhase) {
            ensureOpen();
            if (onePhase) {
                if (activeTransaction == null) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                            "No active transaction to commit");
                }
                commitTransaction(activeTransaction);
                activeTransaction = null;
                return;
            }
            commitPreparedXa(xid);
        }

        @Override
        public void xaRollback(EngineApi.XidDescriptor xid) {
            ensureOpen();
            if (activeTransaction != null) {
                activeTransaction.rollbackAll();
                activeTransaction = null;
                return;
            }
            rollbackPreparedXa(xid);
        }

        @Override
        public List<EngineApi.XidDescriptor> xaRecover() {
            ensureOpen();
            return recoverPreparedXa();
        }

        private EngineApi.StatementResult executeStatement(SqlFrontend.Statement statement,
                                                           Common.ExecutionControl executionControl) {
            executionControl.check();
            if (statement instanceof SqlFrontend.BeginStatement begin) {
                transaction().begin(begin.isolationLevel());
                return new EngineApi.StatementResult("BEGIN", 0, Common.TupleBatch.empty(),
                        Common.TupleBatch.empty(), "BEGIN " + begin.isolationLevel());
            }
            if (statement instanceof SqlFrontend.CommitStatement) {
                transaction().commit();
                return new EngineApi.StatementResult("COMMIT", 0, Common.TupleBatch.empty(), Common.TupleBatch.empty(), "COMMIT");
            }
            if (statement instanceof SqlFrontend.RollbackStatement) {
                transaction().rollback();
                return new EngineApi.StatementResult("ROLLBACK", 0, Common.TupleBatch.empty(), Common.TupleBatch.empty(), "ROLLBACK");
            }
            Transactions.TransactionState transactionState = activeTransaction;
            boolean implicit = false;
            if (transactionState == null) {
                transactionState = transactionManager.begin(Common.IsolationLevel.READ_COMMITTED, committedCatalog);
                implicit = true;
            }
            String statementSavepoint = null;
            if (!implicit) {
                statementSavepoint = "__stmt_" + (++statementCounter);
                transactionState.savepoint(statementSavepoint);
            }
            try {
                EngineApi.StatementResult result = executeWithinTransaction(statement, transactionState, false, executionControl);
                if (implicit) {
                    commitTransaction(transactionState);
                }
                return result;
            } catch (RuntimeException exception) {
                if (implicit) {
                    transactionState.rollbackAll();
                } else if (statementSavepoint != null) {
                    transactionState.rollbackToSavepoint(statementSavepoint);
                }
                throw exception;
            }
        }

        private EngineApi.StatementResult executeWithinTransaction(SqlFrontend.Statement statement,
                                                                  Transactions.TransactionState transactionState,
                                                                  boolean nestedRoutine,
                                                                  Common.ExecutionControl executionControl) {
            executionControl.check();
            if (nestedRoutine && (statement instanceof SqlFrontend.BeginStatement
                    || statement instanceof SqlFrontend.CommitStatement
                    || statement instanceof SqlFrontend.RollbackStatement)) {
                throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                        "Transaction control statements are not allowed inside routines", statement.span());
            }
            Catalog.CatalogSnapshot visibleCatalog = transactionState.catalogSnapshot();
            authorizeStatement(statement, visibleCatalog);
            if (statement instanceof SqlFrontend.CallStatement call) {
                return executeRoutineCall(call, transactionState, visibleCatalog, executionControl);
            }
            if (statement instanceof SqlFrontend.ExplainStatement explainStatement
                    && explainStatement.statement() instanceof SqlFrontend.CallStatement call) {
                Catalog.RoutineDefinition routine = visibleCatalog.routine(Catalog.QualifiedName.from(call.routineName()))
                        .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                                "Unknown routine " + Catalog.QualifiedName.from(call.routineName()).toSql(), call.span()));
                return new EngineApi.StatementResult("EXPLAIN", 0, Common.TupleBatch.empty(), Common.TupleBatch.empty(),
                        "RoutineCall(name=" + routine.name().toSql() + ", kind=" + routine.kind() + ")");
            }

            Planner.Binder binder = new Planner.Binder(visibleCatalog, nextObjectId::getAndIncrement);
            SqlFrontend.Statement explainTarget = statement instanceof SqlFrontend.ExplainStatement explainStatement
                    ? explainStatement.statement()
                    : statement;
            Planner.BoundStatement boundStatement = binder.bind(explainTarget);
            Planner.PhysicalPlan plan = new Planner.Optimizer(visibleCatalog, committedIndexes, committedStorage).optimize(boundStatement);
            if (statement instanceof SqlFrontend.ExplainStatement) {
                return new EngineApi.StatementResult("EXPLAIN", 0, Common.TupleBatch.empty(), Common.TupleBatch.empty(), plan.explain());
            }
            Execution.ExecutionResult result = executor.execute(plan, new Execution.ExecutionContext(
                    visibleCatalog, committedStorage, committedIndexes,
                    currentStatementSnapshotSequence(transactionState), transactionState, sequenceAllocator(), executionControl));
            return new EngineApi.StatementResult(result.commandTag(), result.updateCount(),
                    result.tupleBatch(), result.generatedKeys(), plan.explain());
        }

        private EngineApi.StatementResult executeRoutineCall(SqlFrontend.CallStatement statement,
                                                             Transactions.TransactionState transactionState,
                                                             Catalog.CatalogSnapshot visibleCatalog,
                                                             Common.ExecutionControl executionControl) {
            Catalog.QualifiedName routineName = Catalog.QualifiedName.from(statement.routineName());
            Catalog.RoutineDefinition routine = visibleCatalog.routine(routineName)
                    .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "Unknown routine " + routineName.toSql(), statement.span()));
            RoutineRuntime.CallOutcome outcome = routineRuntime(transactionState, executionControl).executeCall(
                    routine, statement.arguments(), transactionState, statement.span());
            return new EngineApi.StatementResult(outcome.commandTag(), 0L, outcome.batch(),
                    Common.TupleBatch.empty(),
                    "RoutineCall(name=" + routine.name().toSql() + ", kind=" + routine.kind() + ")");
        }

        private RoutineRuntime routineRuntime(Transactions.TransactionState transactionState,
                                             Common.ExecutionControl executionControl) {
            return new RoutineRuntime(transactionState.catalogSnapshot(), sequenceAllocator(),
                    (statement, innerTransactionState) -> executeWithinTransaction(statement, innerTransactionState, true,
                            executionControl),
                    (routine, arguments, innerTransactionState) -> routineRuntime(innerTransactionState, executionControl)
                            .invokeFunction(routine, arguments, innerTransactionState));
        }

        private Execution.SequenceAllocator sequenceAllocator() {
            return new Execution.SequenceAllocator() {
                @Override
                public Common.Value nextValue(Catalog.SequenceDefinition sequence) {
                    return Common.Value.bigint(sequenceStateStore.nextValue(sequence));
                }

                @Override
                public Common.Value nextIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column) {
                    long value = sequenceStateStore.nextIdentityValue(table, column);
                    return column.type() == Common.DataType.INTEGER
                            ? Common.Value.integer(Math.toIntExact(value))
                            : Common.Value.bigint(value);
                }

                @Override
                public void observeIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column, Common.Value value) {
                    sequenceStateStore.observeIdentityValue(table, column, value);
                }
            };
        }

        @Override
        public EngineApi.TransactionHandle transaction() {
            return new EngineApi.TransactionHandle() {
                @Override
                public void begin(Common.IsolationLevel isolationLevel) {
                    if (activeTransaction != null) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Transaction already active");
                    }
                    activeTransaction = transactionManager.begin(isolationLevel, committedCatalog);
                }

                @Override
                public void commit() {
                    if (activeTransaction == null) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "No active transaction");
                    }
                    commitTransaction(activeTransaction);
                    activeTransaction = null;
                }

                @Override
                public void rollback() {
                    if (activeTransaction == null) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "No active transaction");
                    }
                    activeTransaction.rollbackAll();
                    activeTransaction = null;
                }

                @Override
                public void savepoint(String name) {
                    if (activeTransaction == null) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "No active transaction");
                    }
                    activeTransaction.savepoint(name);
                }

                @Override
                public void rollbackToSavepoint(String name) {
                    if (activeTransaction == null) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "No active transaction");
                    }
                    activeTransaction.rollbackToSavepoint(name);
                }

                @Override
                public boolean active() {
                    return activeTransaction != null;
                }
            };
        }

        @Override
        public void close() {
            if (activeTransaction != null) {
                activeTransaction.rollbackAll();
                activeTransaction = null;
            }
            preparedStatements.clear();
        }

        private void authorizeStatement(SqlFrontend.Statement statement, Catalog.CatalogSnapshot visibleCatalog) {
            if ("system".equalsIgnoreCase(principal)) {
                return;
            }
            SqlFrontend.Statement target = statement instanceof SqlFrontend.ExplainStatement explainStatement
                    ? explainStatement.statement()
                    : statement;
            switch (target) {
                case SqlFrontend.SelectStatement select -> requireTablePrivilege(visibleCatalog,
                        Catalog.QualifiedName.from(select.from()), Catalog.Privilege.SELECT, target.span());
                case SqlFrontend.InsertStatement insert -> requireTablePrivilege(visibleCatalog,
                        Catalog.QualifiedName.from(insert.tableName()), Catalog.Privilege.INSERT, target.span());
                case SqlFrontend.UpdateStatement update -> requireTablePrivilege(visibleCatalog,
                        Catalog.QualifiedName.from(update.tableName()), Catalog.Privilege.UPDATE, target.span());
                case SqlFrontend.DeleteStatement delete -> requireTablePrivilege(visibleCatalog,
                        Catalog.QualifiedName.from(delete.tableName()), Catalog.Privilege.DELETE, target.span());
                case SqlFrontend.CallStatement call -> requireRoutinePrivilege(visibleCatalog,
                        Catalog.QualifiedName.from(call.routineName()), Catalog.Privilege.EXECUTE, target.span());
                case SqlFrontend.CreateSchemaStatement createSchema ->
                        requireAdmin(visibleCatalog, createSchema.span());
                case SqlFrontend.CreateTableStatement createTable ->
                        requireAdmin(visibleCatalog, createTable.span());
                case SqlFrontend.CreateIndexStatement createIndex ->
                        requireAdmin(visibleCatalog, createIndex.span());
                case SqlFrontend.CreateSequenceStatement createSequence ->
                        requireAdmin(visibleCatalog, createSequence.span());
                case SqlFrontend.CreateRoutineStatement createRoutine ->
                        requireAdmin(visibleCatalog, createRoutine.span());
                case SqlFrontend.CreateUserStatement createUser ->
                        requireAdmin(visibleCatalog, createUser.span());
                case SqlFrontend.CreateRoleStatement createRole ->
                        requireAdmin(visibleCatalog, createRole.span());
                case SqlFrontend.GrantRoleStatement grantRole ->
                        requireAdmin(visibleCatalog, grantRole.span());
                case SqlFrontend.GrantPrivilegeStatement grantPrivilege ->
                        requireAdmin(visibleCatalog, grantPrivilege.span());
                default -> {
                }
            }
        }

        private void requireAdmin(Catalog.CatalogSnapshot visibleCatalog, Common.SourceSpan span) {
            if (!visibleCatalog.hasPrivilege(principal, Catalog.Privilege.ADMIN, null)) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Principal " + principal + " is not allowed to administer the catalog", span);
            }
        }

        private void requireTablePrivilege(Catalog.CatalogSnapshot visibleCatalog, Catalog.QualifiedName tableName,
                                           Catalog.Privilege privilege, Common.SourceSpan span) {
            Catalog.TableDefinition table = visibleCatalog.requireTable(tableName);
            if (!visibleCatalog.hasPrivilege(principal, privilege, table.id())) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Principal " + principal + " lacks " + privilege + " on table " + tableName.toSql(), span);
            }
        }

        private void requireRoutinePrivilege(Catalog.CatalogSnapshot visibleCatalog, Catalog.QualifiedName routineName,
                                             Catalog.Privilege privilege, Common.SourceSpan span) {
            Catalog.RoutineDefinition routine = visibleCatalog.routine(routineName)
                    .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "Unknown routine " + routineName.toSql(), span));
            if (!visibleCatalog.hasPrivilege(principal, privilege, routine.id())) {
                throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT,
                        "Principal " + principal + " lacks " + privilege + " on routine " + routineName.toSql(), span);
            }
        }

        private List<Common.ResultColumn> describeStatement(SqlFrontend.Statement statement) {
            Catalog.CatalogSnapshot visibleCatalog = activeTransaction == null ? committedCatalog : activeTransaction.catalogSnapshot();
            if (statement instanceof SqlFrontend.ExplainStatement) {
                return List.of(new Common.ResultColumn("PLAN", Common.DataType.TEXT));
            }
            if (statement instanceof SqlFrontend.CallStatement call) {
                return describeCall(call, visibleCatalog);
            }
            Planner.BoundStatement bound = new Planner.Binder(visibleCatalog, () -> -1L).bind(statement);
            if (bound instanceof Planner.BoundSelect select) {
                return describeBoundSelect(select);
            }
            return List.of();
        }

        private List<Common.ResultColumn> describeCall(SqlFrontend.CallStatement statement, Catalog.CatalogSnapshot visibleCatalog) {
            Catalog.RoutineDefinition routine = visibleCatalog.routine(Catalog.QualifiedName.from(statement.routineName()))
                    .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "Unknown routine " + Catalog.QualifiedName.from(statement.routineName()).toSql(), statement.span()));
            if (routine.kind() == Catalog.RoutineKind.FUNCTION) {
                return List.of(new Common.ResultColumn("RETURN_VALUE", routine.returnType(),
                        routine.returnPrecision(), routine.returnScale()));
            }
            return routine.parameters().stream()
                    .filter(parameter -> parameter.mode() != Catalog.ParameterMode.IN)
                    .map(parameter -> new Common.ResultColumn(parameter.name(), parameter.type(),
                            parameter.precision(), parameter.scale()))
                    .toList();
        }

        private List<Common.ResultColumn> describeBoundSelect(Planner.BoundSelect select) {
            return select.items().stream()
                    .map(item -> {
                        Integer precision = null;
                        Integer scale = null;
                        if (item.expression() instanceof Planner.BoundColumnRef columnRef) {
                            Catalog.ColumnDefinition column = select.table().columns().get(columnRef.ordinal());
                            precision = column.precision();
                            scale = column.scale();
                        } else if (item.expression().type() == Common.DataType.DECIMAL) {
                            precision = Common.DataType.DECIMAL.defaultPrecision();
                            scale = Common.DataType.DECIMAL.defaultScale();
                        }
                        return new Common.ResultColumn(item.outputName(), item.expression().type(), precision, scale);
                    })
                    .toList();
        }

        private List<EngineApi.ParameterDescription> describeParameters(SqlFrontend.Statement statement,
                                                                        Catalog.CatalogSnapshot visibleCatalog,
                                                                        Planner.Binder binder) {
            return switch (statement) {
                case SqlFrontend.CallStatement call -> describeCallParameters(call, visibleCatalog);
                case SqlFrontend.ExplainStatement explain when explain.statement() instanceof SqlFrontend.CallStatement call ->
                        describeCallParameters(call, visibleCatalog);
                case SqlFrontend.ExplainStatement explain -> {
                    binder.bind(explain.statement());
                    yield toEngineParameterDescriptions(binder.parameterDescriptions());
                }
                default -> {
                    binder.bind(statement);
                    yield toEngineParameterDescriptions(binder.parameterDescriptions());
                }
            };
        }

        private List<EngineApi.ParameterDescription> describeCallParameters(SqlFrontend.CallStatement statement,
                                                                            Catalog.CatalogSnapshot visibleCatalog) {
            Catalog.RoutineDefinition routine = visibleCatalog.routine(Catalog.QualifiedName.from(statement.routineName()))
                    .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                            "Unknown routine " + Catalog.QualifiedName.from(statement.routineName()).toSql(), statement.span()));
            List<EngineApi.ParameterDescription> parameters = new ArrayList<>();
            int index = 1;
            for (Catalog.RoutineParameter parameter : routine.parameters()) {
                parameters.add(new EngineApi.ParameterDescription(index++, parameter.type(),
                        parameter.precision(), parameter.scale(), true));
            }
            return parameters;
        }

        private List<EngineApi.ParameterDescription> toEngineParameterDescriptions(List<Planner.PreparedParameterDescription> descriptions) {
            return descriptions.stream()
                    .map(parameter -> new EngineApi.ParameterDescription(parameter.index(), parameter.type(),
                            parameter.precision(), parameter.scale(), parameter.nullable()))
                    .toList();
        }

        private record PreparedStatementState(PreparedSqlTemplate template,
                                              EngineApi.PreparedStatementDescription description) {
        }
    }
}
