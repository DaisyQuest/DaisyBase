package dev.daisybase.jdbc;

import dev.daisybase.common.Common;
import dev.daisybase.engine.RemoteProtocol;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;

final class DaisyBaseExceptionFactory {
    private DaisyBaseExceptionFactory() {
    }

    static SQLException fromException(Exception exception) {
        if (exception instanceof SQLException sqlException) {
            return sqlException;
        }
        if (exception instanceof Common.DatabaseException databaseException) {
            return fromCode(databaseException.code().name(), databaseException.getMessage(), databaseException);
        }
        if (exception instanceof RemoteProtocol.ProtocolException protocolException) {
            return new SQLNonTransientException(protocolException.getMessage(), "08S01", protocolException);
        }
        return new SQLNonTransientException(exception.getMessage(), "58005", exception);
    }

    static SQLException fromFailure(RemoteProtocol.Failure failure) {
        return fromCode(failure.code(), failure.message(), null);
    }

    private static SQLException fromCode(String code, String message, Exception cause) {
        return switch (code) {
            case "PARSE_ERROR", "SEMANTIC_ERROR" -> new SQLSyntaxErrorException(message, "42000", cause);
            case "CONSTRAINT_VIOLATION" -> new SQLIntegrityConstraintViolationException(message, "23000", cause);
            case "TRANSACTION_CONFLICT" -> new SQLTransactionRollbackException(message, "40001", cause);
            case "QUERY_CANCELLED" -> new SQLNonTransientException(message, "57014", cause);
            case "QUERY_TIMEOUT" -> new SQLTimeoutException(message, "57014", cause);
            case "AUTHENTICATION_FAILED" -> new SQLInvalidAuthorizationSpecException(message, "28000", cause);
            case "UNSUPPORTED_FEATURE" -> new SQLFeatureNotSupportedException(message, "0A000", cause);
            case "STORAGE_ERROR" -> new SQLNonTransientException(message, "58000", cause);
            case "INTERNAL_ERROR" -> new SQLNonTransientException(message, "58005", cause);
            default -> new SQLNonTransientException(message, "HY000", cause);
        };
    }
}
