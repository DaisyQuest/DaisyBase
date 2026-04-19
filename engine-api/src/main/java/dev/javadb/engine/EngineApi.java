package dev.javadb.engine;

import dev.javadb.common.Common;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class EngineApi {
    private EngineApi() {
    }

    public record DatabaseConfig(Path home, int checkpointInterval, boolean strictDurability) {
        public DatabaseConfig {
            checkpointInterval = checkpointInterval <= 0 ? 8 : checkpointInterval;
        }

        public static DatabaseConfig defaults(Path home) {
            return new DatabaseConfig(home, 8, true);
        }
    }

    public interface DatabaseEngine extends AutoCloseable {
        Session openSession();
        void checkpoint();
        @Override
        void close();
    }

    public interface Session extends AutoCloseable {
        BatchResult execute(String sql);
        BatchResult execute(String sql, Common.ExecutionControl executionControl);
        PreparedStatementDescription prepare(String sql);
        BatchResult executePrepared(long statementId, List<String> parameterLiterals, Common.ExecutionControl executionControl);
        void closePrepared(long statementId);
        TransactionHandle transaction();
        @Override
        void close();
    }

    public record ParameterDescription(int index, Common.DataType type, Integer precision, Integer scale, boolean nullable) {
        public ParameterDescription {
            if (index <= 0) {
                throw new IllegalArgumentException("index must be positive");
            }
            Objects.requireNonNull(type, "type");
        }
    }

    public record PreparedStatementDescription(long statementId, String sql, int parameterCount,
                                               List<ParameterDescription> parameterDescriptions,
                                               List<Common.ResultColumn> resultColumns) {
        public PreparedStatementDescription {
            if (statementId < 0) {
                throw new IllegalArgumentException("statementId must be non-negative");
            }
            Objects.requireNonNull(sql, "sql");
            if (parameterCount < 0) {
                throw new IllegalArgumentException("parameterCount must be non-negative");
            }
            parameterDescriptions = parameterDescriptions == null ? List.of() : List.copyOf(parameterDescriptions);
            if (parameterDescriptions.size() != parameterCount) {
                throw new IllegalArgumentException("parameterDescriptions must match parameterCount");
            }
            resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
        }

        public boolean producesResultSet() {
            return !resultColumns.isEmpty();
        }
    }

    public interface TransactionHandle {
        void begin(Common.IsolationLevel isolationLevel);
        void commit();
        void rollback();
        void savepoint(String name);
        void rollbackToSavepoint(String name);
        boolean active();
    }

    public record StatementResult(String commandTag, long updateCount, Common.TupleBatch batch,
                                  Common.TupleBatch generatedKeys, String explainPlan) {
    }

    public record BatchResult(List<StatementResult> statements) {
        public BatchResult {
            statements = List.copyOf(statements);
        }
    }
}
