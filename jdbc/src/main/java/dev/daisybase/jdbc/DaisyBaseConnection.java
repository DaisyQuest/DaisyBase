package dev.daisybase.jdbc;

import dev.daisybase.common.Common;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.engine.EngineIntrospection;

import javax.sql.rowset.CachedRowSet;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

final class DaisyBaseConnection implements Connection {
    private final DaisyBaseUrl url;
    private final DaisyBaseTransport transport;
    private final Set<DaisyBaseStatement> openStatements = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicInteger savepointIds = new AtomicInteger();
    private boolean autoCommit = true;
    private boolean transactionActive;
    private boolean closed;
    private boolean readOnly;
    private int isolationLevel = Connection.TRANSACTION_READ_COMMITTED;
    private String schema = "public";
    private final Properties clientInfo = new Properties();
    private SQLWarning warning;

    DaisyBaseConnection(DaisyBaseUrl url) throws SQLException {
        this.url = Objects.requireNonNull(url, "url");
        this.transport = switch (url.mode()) {
            case EMBEDDED -> new EmbeddedDaisyBaseTransport(url);
            case REMOTE -> new RemoteDaisyBaseTransport(url);
        };
    }

    EngineApi.BatchResult executeSql(String sql, long executionId, long timeoutMillis) throws SQLException {
        ensureOpen();
        if (!autoCommit && !transactionActive && !isTransactionControlSql(sql)) {
            transport.begin(toIsolationLevel(isolationLevel));
            transactionActive = true;
        }
        EngineApi.BatchResult result = transport.execute(sql, executionId, timeoutMillis);
        synchronizeTransactionState(result);
        return result;
    }

    DaisyBaseTransport.PreparedHandle prepareSql(String sql) throws SQLException {
        ensureOpen();
        return transport.prepare(sql);
    }

    EngineApi.BatchResult executePrepared(long statementId, List<String> parameterLiterals,
                                          long executionId, long timeoutMillis) throws SQLException {
        ensureOpen();
        if (!autoCommit && !transactionActive) {
            transport.begin(toIsolationLevel(isolationLevel));
            transactionActive = true;
        }
        EngineApi.BatchResult result = transport.executePrepared(statementId, parameterLiterals, executionId, timeoutMillis);
        synchronizeTransactionState(result);
        return result;
    }

    void closePrepared(long statementId) throws SQLException {
        ensureOpen();
        transport.closePrepared(statementId);
    }

    long nextExecutionId() {
        return transport.nextExecutionId();
    }

    boolean cancelExecution(long executionId) throws SQLException {
        ensureOpen();
        return transport.cancel(executionId);
    }

    void xaPrepare(EngineApi.XidDescriptor xid) throws SQLException {
        ensureOpen();
        transport.xaPrepare(xid);
        transactionActive = false;
    }

    void xaCommit(EngineApi.XidDescriptor xid, boolean onePhase) throws SQLException {
        ensureOpen();
        transport.xaCommit(xid, onePhase);
        transactionActive = false;
    }

    void xaRollback(EngineApi.XidDescriptor xid) throws SQLException {
        ensureOpen();
        transport.xaRollback(xid);
        transactionActive = false;
    }

    List<EngineApi.XidDescriptor> xaRecover() throws SQLException {
        ensureOpen();
        return transport.xaRecover();
    }

    void ensureTransactionStarted() throws SQLException {
        ensureOpen();
        beginIfNeeded();
    }

    CachedRowSet metadata(EngineIntrospection.MetadataQuery query, List<String> arguments) throws SQLException {
        ensureOpen();
        return DaisyBaseResultSets.fromTupleBatch(transport.metadata(query, arguments), 0);
    }

    DatabaseMetaData databaseMetaData() {
        return DaisyBaseDatabaseMetaData.create(this);
    }

    String jdbcUrl() {
        return url.url();
    }

    String jdbcUser() {
        return url.user();
    }

    void register(DaisyBaseStatement statement) {
        openStatements.add(statement);
    }

    void unregister(DaisyBaseStatement statement) {
        openStatements.remove(statement);
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        ensureResultSetSupport(resultSetType, resultSetConcurrency);
        DaisyBaseStatement statement = new DaisyBaseStatement(this, resultSetType, resultSetConcurrency);
        register(statement);
        return statement;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        ensureHoldability(resultSetHoldability);
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public DaisyBasePreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, false);
    }

    @Override
    public DaisyBasePreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency, false);
    }

    private DaisyBasePreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                                     boolean returnGeneratedKeys) throws SQLException {
        ensureResultSetSupport(resultSetType, resultSetConcurrency);
        DaisyBasePreparedStatement statement = new DaisyBasePreparedStatement(this, resultSetType, resultSetConcurrency, sql, returnGeneratedKeys);
        register(statement);
        return statement;
    }

    @Override
    public DaisyBasePreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        ensureHoldability(resultSetHoldability);
        return prepareStatement(sql, resultSetType, resultSetConcurrency, false);
    }

    @Override
    public DaisyBasePreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS && autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS) {
            throw new SQLException("Invalid autoGeneratedKeys value: " + autoGeneratedKeys);
        }
        return prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY,
                autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS);
    }

    @Override
    public DaisyBasePreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY,
                columnIndexes != null && columnIndexes.length > 0);
    }

    @Override
    public DaisyBasePreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY,
                columnNames != null && columnNames.length > 0);
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
        return prepareCall(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        ensureResultSetSupport(resultSetType, resultSetConcurrency);
        DaisyBaseCallableStatement statement = new DaisyBaseCallableStatement(this, resultSetType, resultSetConcurrency, sql);
        register(statement);
        return statement;
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        ensureHoldability(resultSetHoldability);
        return prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public String nativeSQL(String sql) {
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        ensureOpen();
        if (this.autoCommit == autoCommit) {
            return;
        }
        if (!this.autoCommit && autoCommit && transactionActive) {
            transport.commit();
            transactionActive = false;
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("Cannot commit while autoCommit is true");
        }
        if (transactionActive) {
            transport.commit();
            transactionActive = false;
        }
    }

    @Override
    public void rollback() throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("Cannot rollback while autoCommit is true");
        }
        if (transactionActive) {
            transport.rollback();
            transactionActive = false;
        }
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("Cannot rollback to savepoint while autoCommit is true");
        }
        DaisyBaseSavepoint javaDbSavepoint = requireSavepoint(savepoint);
        if (javaDbSavepoint.released()) {
            throw new SQLException("Savepoint has been released");
        }
        beginIfNeeded();
        transport.rollbackToSavepoint(javaDbSavepoint.jdbcName());
        transactionActive = true;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return setSavepoint(null);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("Cannot create savepoint while autoCommit is true");
        }
        beginIfNeeded();
        DaisyBaseSavepoint savepoint = new DaisyBaseSavepoint(savepointIds.incrementAndGet(), name == null || name.isBlank() ? null : name);
        transport.savepoint(savepoint.jdbcName());
        transactionActive = true;
        return savepoint;
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        requireSavepoint(savepoint).markReleased();
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        SQLException firstFailure = null;
        for (DaisyBaseStatement statement : List.copyOf(openStatements)) {
            try {
                statement.close();
            } catch (SQLException sqlException) {
                if (firstFailure == null) {
                    firstFailure = sqlException;
                }
            }
        }
        if (!autoCommit && transactionActive) {
            try {
                transport.rollback();
            } catch (SQLException sqlException) {
                if (firstFailure == null) {
                    firstFailure = sqlException;
                }
            }
        }
        try {
            transport.close();
        } catch (SQLException sqlException) {
            if (firstFailure == null) {
                firstFailure = sqlException;
            }
        }
        closed = true;
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        ensureOpen();
        return databaseMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        ensureOpen();
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        if (catalog != null && !catalog.isBlank()) {
            throw new SQLFeatureNotSupportedException("Catalogs are not supported");
        }
    }

    @Override
    public String getCatalog() {
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        ensureOpen();
        if (transactionActive) {
            throw new SQLException("Cannot change isolation during an active transaction");
        }
        switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED,
                    Connection.TRANSACTION_READ_COMMITTED,
                    Connection.TRANSACTION_REPEATABLE_READ,
                    Connection.TRANSACTION_SERIALIZABLE -> isolationLevel = level;
            default -> throw new SQLFeatureNotSupportedException("Unsupported transaction isolation level: " + level);
        }
    }

    @Override
    public int getTransactionIsolation() {
        return isolationLevel;
    }

    @Override
    public SQLWarning getWarnings() {
        return warning;
    }

    @Override
    public void clearWarnings() {
        warning = null;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() {
        return Map.of();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        ensureHoldability(holdability);
    }

    @Override
    public int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public java.sql.Clob createClob() throws SQLException {
        ensureOpen();
        return new DaisyBaseClob();
    }

    @Override
    public java.sql.Blob createBlob() throws SQLException {
        ensureOpen();
        return new DaisyBaseBlob();
    }

    @Override
    public java.sql.NClob createNClob() throws SQLException {
        ensureOpen();
        return new DaisyBaseNClob();
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws SQLException {
        ensureOpen();
        return new DaisyBaseSqlXml();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("timeout must be non-negative");
        }
        if (closed) {
            return false;
        }
        transport.ping();
        return true;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (name == null || name.isBlank()) {
            return;
        }
        if (value == null) {
            clientInfo.remove(name);
        } else {
            clientInfo.setProperty(name, value);
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        clientInfo.clear();
        if (properties != null) {
            for (String name : properties.stringPropertyNames()) {
                clientInfo.setProperty(name, properties.getProperty(name));
            }
        }
    }

    @Override
    public String getClientInfo(String name) {
        return clientInfo.getProperty(name);
    }

    @Override
    public Properties getClientInfo() {
        Properties copy = new Properties();
        copy.putAll(clientInfo);
        return copy;
    }

    @Override
    public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        ensureOpen();
        if (typeName == null || typeName.isBlank()) {
            throw new SQLException("typeName must not be blank");
        }
        return new DaisyBaseArray(typeName, elements);
    }

    @Override
    public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        ensureOpen();
        if (typeName == null || typeName.isBlank()) {
            throw new SQLException("typeName must not be blank");
        }
        return new DaisyBaseStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        ensureOpen();
        if (schema == null || schema.isBlank() || schema.equalsIgnoreCase("public")) {
            this.schema = "public";
            return;
        }
        throw new SQLFeatureNotSupportedException("Only the public schema is currently supported");
    }

    @Override
    public String getSchema() {
        return schema;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("executor must not be null");
        }
        executor.execute(() -> {
            try {
                close();
            } catch (SQLException ignored) {
            }
        });
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        ensureOpen();
        if (executor == null) {
            throw new SQLException("executor must not be null");
        }
        if (milliseconds < 0) {
            throw new SQLException("milliseconds must be non-negative");
        }
        transport.setNetworkTimeoutMillis(milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        ensureOpen();
        return transport.getNetworkTimeoutMillis();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    private void beginIfNeeded() throws SQLException {
        if (!transactionActive) {
            transport.begin(toIsolationLevel(isolationLevel));
            transactionActive = true;
        }
    }

    private void synchronizeTransactionState(EngineApi.BatchResult result) {
        for (EngineApi.StatementResult statement : result.statements()) {
            String tag = statement.commandTag().toUpperCase(Locale.ROOT);
            if (tag.equals("BEGIN")) {
                transactionActive = true;
            } else if (tag.equals("COMMIT") || tag.equals("ROLLBACK")) {
                transactionActive = false;
            }
        }
    }

    private boolean isTransactionControlSql(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("BEGIN") || upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK");
    }

    private Common.IsolationLevel toIsolationLevel(int level) {
        return switch (level) {
            case Connection.TRANSACTION_REPEATABLE_READ -> Common.IsolationLevel.REPEATABLE_READ;
            case Connection.TRANSACTION_SERIALIZABLE -> Common.IsolationLevel.SERIALIZABLE;
            case Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED -> Common.IsolationLevel.READ_COMMITTED;
            default -> Common.IsolationLevel.READ_COMMITTED;
        };
    }

    private DaisyBaseSavepoint requireSavepoint(Savepoint savepoint) throws SQLException {
        if (!(savepoint instanceof DaisyBaseSavepoint javaDbSavepoint)) {
            throw new SQLException("Savepoint did not originate from this driver");
        }
        return javaDbSavepoint;
    }

    private void ensureResultSetSupport(int resultSetType, int resultSetConcurrency) throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY && resultSetType != ResultSet.TYPE_SCROLL_INSENSITIVE) {
            throw new SQLFeatureNotSupportedException("Unsupported result set type: " + resultSetType);
        }
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY
                && resultSetConcurrency != ResultSet.CONCUR_UPDATABLE) {
            throw new SQLFeatureNotSupportedException("Unsupported result set concurrency: " + resultSetConcurrency);
        }
    }

    private void ensureHoldability(int holdability) throws SQLException {
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLFeatureNotSupportedException("Only CLOSE_CURSORS_AT_COMMIT is supported");
        }
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }
}
