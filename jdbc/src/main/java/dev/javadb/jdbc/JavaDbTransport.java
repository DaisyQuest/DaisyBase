package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineApi;
import dev.javadb.engine.EngineIntrospection;

import java.sql.SQLException;
import java.util.List;

interface JavaDbTransport extends AutoCloseable {
    record PreparedHandle(long statementId, int parameterCount, List<EngineApi.ParameterDescription> parameterDescriptions,
                          List<Common.ResultColumn> resultColumns) {
        public PreparedHandle {
            parameterDescriptions = parameterDescriptions == null ? List.of() : List.copyOf(parameterDescriptions);
            resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
        }
    }

    long nextExecutionId();

    EngineApi.BatchResult execute(String sql, long executionId, long timeoutMillis) throws SQLException;

    PreparedHandle prepare(String sql) throws SQLException;

    EngineApi.BatchResult executePrepared(long statementId, List<String> parameterLiterals,
                                         long executionId, long timeoutMillis) throws SQLException;

    void closePrepared(long statementId) throws SQLException;

    boolean cancel(long executionId) throws SQLException;

    void begin(Common.IsolationLevel isolationLevel) throws SQLException;

    void commit() throws SQLException;

    void rollback() throws SQLException;

    void savepoint(String name) throws SQLException;

    void rollbackToSavepoint(String name) throws SQLException;

    boolean active() throws SQLException;

    void xaPrepare(EngineApi.XidDescriptor xid) throws SQLException;

    void xaCommit(EngineApi.XidDescriptor xid, boolean onePhase) throws SQLException;

    void xaRollback(EngineApi.XidDescriptor xid) throws SQLException;

    List<EngineApi.XidDescriptor> xaRecover() throws SQLException;

    Common.TupleBatch metadata(EngineIntrospection.MetadataQuery query, List<String> arguments) throws SQLException;

    void ping() throws SQLException;

    void setNetworkTimeoutMillis(int milliseconds) throws SQLException;

    int getNetworkTimeoutMillis();

    @Override
    void close() throws SQLException;
}
