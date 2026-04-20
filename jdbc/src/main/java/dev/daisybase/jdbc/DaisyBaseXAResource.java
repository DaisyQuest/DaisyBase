package dev.daisybase.jdbc;

import dev.daisybase.engine.EngineApi;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

final class DaisyBaseXAResource implements XAResource {
    private final DaisyBaseConnection connection;
    private final String resourceManagerId;
    private XidKey currentXid;
    private boolean ended;
    private boolean prepared;
    private boolean rollbackOnly;
    private int transactionTimeoutSeconds;

    DaisyBaseXAResource(DaisyBaseConnection connection) {
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
                    connection.ensureTransactionStarted();
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
        try {
            connection.xaPrepare(key.toDescriptor());
            prepared = true;
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
        return XA_OK;
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        XidKey key = XidKey.of(xid);
        try {
            if (onePhase) {
                requireCurrent(key);
                if (rollbackOnly) {
                    throw xa(XAException.XA_RBROLLBACK, "XA branch is marked rollback-only");
                }
                if (!ended) {
                    throw xa(XAException.XAER_PROTO, "XA branch must be ended before commit");
                }
                connection.xaCommit(key.toDescriptor(), true);
                resetState();
                return;
            }
            connection.xaCommit(key.toDescriptor(), false);
            if (currentXid != null && currentXid.equals(key)) {
                resetState();
            }
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        XidKey key = XidKey.of(xid);
        try {
            connection.xaRollback(key.toDescriptor());
            if (currentXid != null && currentXid.equals(key)) {
                resetState();
            }
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    @Override
    public void forget(Xid xid) throws XAException {
        XidKey key = XidKey.of(xid);
        if (currentXid != null && currentXid.equals(key)) {
            resetState();
        }
    }

    @Override
    public Xid[] recover(int flag) throws XAException {
        if (flag != TMSTARTRSCAN && flag != TMENDRSCAN && flag != TMNOFLAGS) {
            throw xa(XAException.XAER_INVAL, "Unsupported XA recover flag: " + flag);
        }
        try {
            return connection.xaRecover().stream()
                    .map(XidKey::fromDescriptor)
                    .map(XidKey::toXid)
                    .toArray(Xid[]::new);
        } catch (SQLException exception) {
            throw xa(XAException.XAER_RMERR, exception.getMessage(), exception);
        }
    }

    @Override
    public boolean isSameRM(XAResource xaResource) {
        return xaResource instanceof DaisyBaseXAResource other
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

        static XidKey fromDescriptor(EngineApi.XidDescriptor xid) {
            return new XidKey(xid.formatId(), xid.globalId(), xid.branchId());
        }

        EngineApi.XidDescriptor toDescriptor() {
            return new EngineApi.XidDescriptor(formatId, globalId, branchId);
        }

        Xid toXid() {
            return new SimpleXid(formatId, globalId, branchId);
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

    private record SimpleXid(int formatId, byte[] globalId, byte[] branchId) implements Xid {
        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalId.clone();
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchId.clone();
        }
    }
}
