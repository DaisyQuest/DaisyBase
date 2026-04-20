package dev.daisybase.wal;

import dev.daisybase.catalog.Catalog;
import dev.daisybase.common.Common;
import dev.daisybase.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsAndRecoversCommittedTransactions() {
        Wal.WalManager walManager = new Wal.WalManager(tempDir);
        walManager.appendCommittedTransaction(new Common.TransactionId(7),
                List.of(new Catalog.CreateSchemaChange(new Common.ObjectId(3), "analytics")),
                List.of(new Wal.MutationRecord("INSERT", new Common.ObjectId(11), new Common.RowId(5),
                        List.of(Common.Value.integer(1), Common.Value.text("Ada")))),
                12,
                true);

        List<Wal.RecoveredTransaction> recovered = walManager.recover();
        assertEquals(1, recovered.size());
        assertEquals(7L, recovered.getFirst().transactionId().value());
        assertEquals(12L, recovered.getFirst().commitSequence());
        assertEquals("analytics", ((Catalog.CreateSchemaChange) recovered.getFirst().catalogChanges().getFirst()).schemaName());
        assertEquals("INSERT", recovered.getFirst().mutationRecords().getFirst().kind());
        assertEquals(5L, recovered.getFirst().mutationRecords().getFirst().rowId().value());
    }

    @Test
    void recoversNullValuesAndRepeatedMutationsWithinCommittedTransaction() {
        Wal.WalManager walManager = new Wal.WalManager(tempDir);
        Common.ObjectId tableId = new Common.ObjectId(11);
        Common.RowId rowId = new Common.RowId(5);
        List<Wal.MutationRecord> mutations = List.of(
                new Wal.MutationRecord("INSERT", tableId, rowId,
                        List.of(Common.Value.integer(1),
                                Common.Value.nullValue(Common.DataType.TEXT),
                                Common.Value.text("Ada|Lovelace, PhD"))),
                new Wal.MutationRecord("UPDATE", tableId, rowId,
                        List.of(Common.Value.integer(2),
                                Common.Value.nullValue(Common.DataType.TEXT),
                                Common.Value.text("Ada Byron"))),
                new Wal.MutationRecord("DELETE", tableId, rowId, List.of())
        );

        walManager.appendCommittedTransaction(new Common.TransactionId(8), List.of(), mutations, 15, false);

        List<Wal.RecoveredTransaction> recovered = walManager.recover();
        assertAll(
                () -> assertEquals(1, recovered.size()),
                () -> assertEquals(8L, recovered.getFirst().transactionId().value()),
                () -> assertEquals(15L, recovered.getFirst().commitSequence()),
                () -> assertEquals(mutations, recovered.getFirst().mutationRecords()),
                () -> assertEquals(recovered, walManager.recover())
        );
    }

    @Test
    void ignoresUncommittedRecordsDuringRecovery() throws Exception {
        Wal.WalManager walManager = new Wal.WalManager(tempDir);
        walManager.appendCommittedTransaction(new Common.TransactionId(7),
                List.of(),
                List.of(new Wal.MutationRecord("INSERT", new Common.ObjectId(11), new Common.RowId(5),
                        List.of(Common.Value.integer(1), Common.Value.text("committed")))),
                12,
                true);

        String trailingUncommittedRecords =
                "99|MUTATION|44|" + Common.Values.encodeString(new Wal.MutationRecord("UPDATE", new Common.ObjectId(11),
                        new Common.RowId(5), List.of(Common.Value.integer(2), Common.Value.text("ignored"))).serialize())
                        + System.lineSeparator()
                        + "100|CATALOG|44|" + Common.Values.encodeString(new Catalog.CreateSchemaChange(new Common.ObjectId(9),
                        "ignored").serialize())
                        + System.lineSeparator();
        Files.writeString(tempDir.resolve("wal.log"), trailingUncommittedRecords, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<Wal.RecoveredTransaction> recovered = walManager.recover();
        assertAll(
                () -> assertEquals(1, recovered.size()),
                () -> assertEquals(7L, recovered.getFirst().transactionId().value()),
                () -> assertEquals(1, recovered.getFirst().mutationRecords().size()),
                () -> assertEquals("committed", recovered.getFirst().mutationRecords().getFirst().values().get(1).asText())
        );
    }

    @Test
    void recoversCommittedPageImagesAlongsideLogicalMutations() {
        Wal.WalManager walManager = new Wal.WalManager(tempDir);
        byte[] header = new byte[]{1, 2, 3, 4};
        byte[] page = new byte[]{9, 8, 7, 6};
        walManager.appendCommittedTransaction(new Common.TransactionId(9),
                List.of(),
                List.of(new Wal.MutationRecord("INSERT", new Common.ObjectId(11), new Common.RowId(5),
                        List.of(Common.Value.integer(1)))),
                List.of(
                        new Storage.PageImage(new Common.ObjectId(11), Storage.FILE_HEADER_PAGE_NUMBER, 0L, header),
                        new Storage.PageImage(new Common.ObjectId(11), 0, 0L, page)
                ),
                22,
                true);

        List<Wal.RecoveredTransaction> recovered = walManager.recover();
        assertAll(
                () -> assertEquals(1, recovered.size()),
                () -> assertEquals(22L, recovered.getFirst().commitSequence()),
                () -> assertEquals(2, recovered.getFirst().pageImages().size()),
                () -> assertEquals(Storage.FILE_HEADER_PAGE_NUMBER, recovered.getFirst().pageImages().get(0).pageNumber()),
                () -> assertTrue(recovered.getFirst().pageImages().get(0).lsn() > 0),
                () -> assertArrayEquals(header, recovered.getFirst().pageImages().get(0).bytes()),
                () -> assertEquals(0, recovered.getFirst().pageImages().get(1).pageNumber()),
                () -> assertTrue(recovered.getFirst().pageImages().get(1).lsn() > recovered.getFirst().pageImages().get(0).lsn()),
                () -> assertArrayEquals(page, recovered.getFirst().pageImages().get(1).bytes())
        );
    }

    @Test
    void persistsMetaUpdatesAndCheckpointTruncatesWal() throws Exception {
        Wal.WalManager walManager = new Wal.WalManager(tempDir);
        walManager.appendCommittedTransaction(new Common.TransactionId(10),
                List.of(),
                List.of(new Wal.MutationRecord("INSERT", new Common.ObjectId(12), new Common.RowId(1),
                        List.of(Common.Value.integer(1)))),
                30,
                false);
        walManager.updateNextObjectId(88);
        walManager.checkpoint(30, 88);

        Wal.WalManager reopened = new Wal.WalManager(tempDir);

        assertAll(
                () -> assertEquals(30L, reopened.meta().lastCommitSequence()),
                () -> assertEquals(88L, reopened.meta().nextObjectId()),
                () -> assertTrue(Files.readString(tempDir.resolve("wal.log"), StandardCharsets.UTF_8).isEmpty()),
                () -> assertTrue(reopened.recover().isEmpty())
        );
    }

    @Test
    void emptyMetaFileFallsBackToFreshDefaults() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("wal.meta"), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Wal.WalManager walManager = new Wal.WalManager(tempDir);

        assertEquals(1L, walManager.meta().nextLsn());
        assertEquals(0L, walManager.meta().lastCommitSequence());
        assertEquals(2L, walManager.meta().nextObjectId());
    }
}
