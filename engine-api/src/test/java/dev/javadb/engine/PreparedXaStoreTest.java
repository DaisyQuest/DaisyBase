package dev.javadb.engine;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.txn.Transactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedXaStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsLoadsRecoversAndDeletesPreparedBranches() {
        PreparedXaStore store = new PreparedXaStore(tempDir);
        PreparedXaStore.PreparedBranch first = branch(10, "alpha");
        PreparedXaStore.PreparedBranch second = branch(11, "beta");

        store.save(second);
        store.save(first);

        PreparedXaStore.PreparedBranch loaded = store.load(first.xid());
        assertEquals(first.xid(), loaded.xid());
        assertEquals(first.preparedState().transactionId(), loaded.preparedState().transactionId());
        assertEquals(List.of(first.xid(), second.xid()), store.recover());
        assertEquals(first.xid().hashCode(), loaded.xid().hashCode());
        assertNotEquals(first.xid(), second.xid());

        store.delete(first.xid());
        assertNull(store.load(first.xid()));
        assertEquals(List.of(second.xid()), store.recover());
    }

    @Test
    void loadReturnsNullForUnknownBranch() {
        PreparedXaStore store = new PreparedXaStore(tempDir);
        assertNull(store.load(new EngineApi.XidDescriptor(44, new byte[]{4}, new byte[]{5})));
        assertTrue(store.recover().isEmpty());
    }

    @Test
    void constructorAndRecoveryReportStorageErrors() throws Exception {
        Path blockedHome = tempDir.resolve("blocked-home");
        Files.writeString(blockedHome, "file-not-directory");
        Common.DatabaseException constructorFailure = assertThrows(Common.DatabaseException.class,
                () -> new PreparedXaStore(blockedHome));
        assertEquals(Common.ErrorCode.STORAGE_ERROR, constructorFailure.code());

        Path corruptHome = tempDir.resolve("corrupt-home");
        PreparedXaStore store = new PreparedXaStore(corruptHome);
        Files.writeString(corruptHome.resolve("xa-prepared").resolve("bad.bin"), "not-a-serialized-branch");
        Files.writeString(corruptHome.resolve("xa-prepared").resolve("ignore.txt"), "ignored");

        Common.DatabaseException recoverFailure = assertThrows(Common.DatabaseException.class, store::recover);
        assertEquals(Common.ErrorCode.STORAGE_ERROR, recoverFailure.code());
        assertTrue(recoverFailure.getMessage().contains("Failed to read prepared XA branch"));
    }

    private PreparedXaStore.PreparedBranch branch(int formatId, String note) {
        Catalog.CatalogSnapshot catalog = Catalog.bootstrap(new Common.ObjectId(1));
        Transactions.TransactionManager manager = new Transactions.TransactionManager(0);
        Transactions.TransactionState transaction = manager.begin(Common.IsolationLevel.READ_COMMITTED, catalog);
        transaction.stageInsert(new Common.ObjectId(99),
                List.of(Common.Value.integer(formatId), Common.Value.text(note)));
        return new PreparedXaStore.PreparedBranch(
                new EngineApi.XidDescriptor(formatId, new byte[]{(byte) formatId}, note.getBytes()),
                transaction.freezeForPrepare());
    }
}
