package dev.javadb.jdbc;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

final class JavaDbXAResource implements XAResource {
    private final JavaDbConnection connection;
    private final String resourceManagerId;
    private XidKey currentXid;
    private boolean ended;
    private boolean prepared;
    private boolean rollbackOnly;
    private int transactionTimeoutSeconds;

    JavaDbXAResource(JavaDbConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.resourceManagerId = connection.jdbcUrl();
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
        XidKey key = XidKey.of(xid);
        try {
            switch (flags) {
                case TMNOFLAGS -> {
                    if (currentXid != null && !currentXid.equals(key)) {
                        throw xa(XAException.XAER_PROTO, "Another XA branch is already active");
                    }
                    connection.setAutoCommit(false);
                    currentXid = key;
                    ended = false;
                    prepared = false;
                    rollbackOnly = false;
                }
                case TMJOIN, TMRESUME -> {
                    requireCurrent(key);
                    ended = false;
                }
                default -> throw xa(XAException.XAER_INVAL, "Unsupported XA start flag: " + flags);
            }
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
        XidKey key = XidKey.of(xid);
        requireCurrent(key);
        switch (flags) {
            case TMSUCCESS, TMSUSPEND -> ended = true;
            case TMFAIL -> {
                ended = true;
                rollbackOnly = true;
            }
            default -> throw xa(XAException.XAER_INVAL, "Unsupported XA end flag: " + flags);
        }
    }

    @Override
    public int prepare(Xid xid) throws XAException {
        XidKey key = XidKey.of(xid);
        requireCurrent(key);
        if (!ended) {
            throw xa(XAException.XAER_PROTO, "XA branch must be ended before prepare");
        }
        if (rollbackOnly) {
            throw xa(XAException.XA_RBROLLBACK, "XA branch is marked rollback-only");
        }
        prepared = true;
        return XA_OK;
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        XidKey key = XidKey.of(xid);
        requireCurrent(key);
        if (rollbackOnly) {
            throw xa(XAException.XA_RBROLLBACK, "XA branch is marked rollback-only");
        }
        if (!onePhase && !prepared) {
            throw xa(XAException.XAER_PROTO, "Two-phase commit requires prepare");
        }
        if (!ended) {
            throw xa(XAException.XAER_PROTO, "XA branch must be ended before commit");
        }
        try {
            connection.commit();
            resetState();
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        XidKey key = XidKey.of(xid);
        requireCurrent(key);
        try {
            connection.rollback();
            resetState();
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    @Override
    public void forget(Xid xid) throws XAException {
        requireCurrent(XidKey.of(xid));
        resetState();
    }

    @Override
    public Xid[] recover(int flag) {
        return new Xid[0];
    }

    @Override
    public boolean isSameRM(XAResource xaResource) {
        return xaResource instanceof JavaDbXAResource other
                && resourceManagerId.equals(other.resourceManagerId);
    }

    @Override
    public boolean setTransactionTimeout(int seconds) {
        transactionTimeoutSeconds = Math.max(seconds, 0);
        return true;
    }

    @Override
    public int getTransactionTimeout() {
        return transactionTimeoutSeconds;
    }

    private void requireCurrent(XidKey key) throws XAException {
        if (currentXid == null || !currentXid.equals(key)) {
            throw xa(XAException.XAER_NOTA, "Unknown XA branch");
        }
    }

    private void resetState() throws XAException {
        currentXid = null;
        ended = false;
        prepared = false;
        rollbackOnly = false;
        try {
            connection.setAutoCommit(true);
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    private static XAException xa(int errorCode, String message) {
        XAException exception = new XAException(message);
        exception.errorCode = errorCode;
        return exception;
    }

    private static XAException xa(int errorCode, String message, Exception cause) {
        XAException exception = xa(errorCode, message);
        exception.initCause(cause);
        return exception;
    }

    private record XidKey(int formatId, byte[] globalId, byte[] branchId) {
        static XidKey of(Xid xid) throws XAException {
            if (xid == null) {
                throw xa(XAException.XAER_INVAL, "xid must not be null");
            }
            return new XidKey(xid.getFormatId(), xid.getGlobalTransactionId().clone(), xid.getBranchQualifier().clone());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof XidKey other
                    && formatId == other.formatId
                    && Arrays.equals(globalId, other.globalId)
                    && Arrays.equals(branchId, other.branchId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(formatId, Arrays.hashCode(globalId), Arrays.hashCode(branchId));
        }
    }
}
