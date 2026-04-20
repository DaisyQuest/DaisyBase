package dev.daisybase.storage;

import dev.daisybase.common.Common;
import dev.daisybase.txn.Transactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {
    @TempDir
    Path tempDir;

    @Test
    void replaysRecoveredMutationsForSameRowIdempotentlyWhenTransactionStartsWithInsert() {
        Common.ObjectId tableId = new Common.ObjectId(3);
        Common.RowId rowId = new Common.RowId(10);
        List<Storage.RecoveredMutation> mutations = List.of(
                new Storage.RecoveredMutation("INSERT", tableId, rowId,
                        List.of(Common.Value.integer(1), Common.Value.nullValue(Common.DataType.TEXT))),
                new Storage.RecoveredMutation("UPDATE", tableId, rowId,
                        List.of(Common.Value.integer(2), Common.Value.text("updated"))),
                new Storage.RecoveredMutation("DELETE", tableId, rowId, List.of())
        );

        Storage.StorageSnapshot recovered = Storage.applyRecoveredMutations(Storage.empty(), mutations, 7);
        Storage.StorageSnapshot replayed = Storage.applyRecoveredMutations(recovered, mutations, 7);
        Storage.TableData tableData = recovered.tables().get(tableId);
        List<Storage.RowVersion> chain = tableData.versionChains().get(rowId);

        assertAll(
                () -> assertEquals(recovered, replayed),
                () -> assertEquals(11L, tableData.nextRowId()),
                () -> assertEquals(2, chain.size()),
                () -> assertEquals(Common.Value.nullValue(Common.DataType.TEXT), chain.getFirst().values().get(1)),
                () -> assertEquals(7L, chain.getFirst().deletedAtCommitSequence()),
                () -> assertEquals(7L, chain.get(1).deletedAtCommitSequence()),
                () -> assertFalse(Storage.rowExists(recovered, tableId, rowId)),
                () -> assertNull(Storage.currentRow(recovered, tableId, rowId, 6)),
                () -> assertNull(Storage.currentRow(recovered, tableId, rowId, 7)),
                () -> assertTrue(Storage.visibleRows(recovered, tableId, 7, null).isEmpty())
        );
    }

    @Test
    void applyCommitAssignsRowMappingsAndVisibleRowsRespectPendingDelta() {
        Common.ObjectId tableId = new Common.ObjectId(17);
        Storage.StorageSnapshot base = Storage.ensureTable(Storage.empty(), tableId);

        Transactions.TableDelta insertDelta = new Transactions.TableDelta();
        Common.RowId tempInsert = new Common.RowId(-1);
        insertDelta.inserts().put(tempInsert, List.of(Common.Value.integer(1), Common.Value.text("alpha")));

        Storage.AppliedCommit inserted = Storage.applyCommit(base, Map.of(tableId, insertDelta), 5);
        Common.RowId actualRowId = inserted.insertedRowMappings().get(tableId).get(tempInsert);

        Transactions.TableDelta overlay = new Transactions.TableDelta();
        overlay.updates().put(actualRowId, List.of(Common.Value.integer(2), Common.Value.text("beta")));
        overlay.inserts().put(new Common.RowId(-2), List.of(Common.Value.integer(3), Common.Value.text("gamma")));

        List<Storage.VisibleRow> visibleRows = Storage.visibleRows(inserted.snapshot(), tableId, 5, overlay);

        assertAll(
                () -> assertEquals(1L, actualRowId.value()),
                () -> assertEquals(List.of(Common.Value.integer(1), Common.Value.text("alpha")),
                        Storage.currentRow(inserted.snapshot(), tableId, actualRowId, 5)),
                () -> assertEquals(2, visibleRows.size()),
                () -> assertEquals(List.of(Common.Value.integer(2), Common.Value.text("beta")), visibleRows.get(0).values()),
                () -> assertEquals(List.of(Common.Value.integer(3), Common.Value.text("gamma")), visibleRows.get(1).values())
        );
    }

    @Test
    void applyCommitRejectsMissingAndDeletedRows() {
        Common.ObjectId tableId = new Common.ObjectId(19);
        Transactions.TableDelta insert = new Transactions.TableDelta();
        insert.inserts().put(new Common.RowId(-1), List.of(Common.Value.integer(1)));
        Storage.AppliedCommit committed = Storage.applyCommit(Storage.empty(), Map.of(tableId, insert), 3);
        Common.RowId rowId = committed.insertedRowMappings().get(tableId).values().iterator().next();

        Transactions.TableDelta delete = new Transactions.TableDelta();
        delete.deletes().add(rowId);
        Storage.StorageSnapshot deletedSnapshot = Storage.applyCommit(committed.snapshot(), Map.of(tableId, delete), 4).snapshot();

        Transactions.TableDelta missingUpdate = new Transactions.TableDelta();
        missingUpdate.updates().put(new Common.RowId(99), List.of(Common.Value.integer(7)));
        Transactions.TableDelta deletedUpdate = new Transactions.TableDelta();
        deletedUpdate.updates().put(rowId, List.of(Common.Value.integer(2)));
        Transactions.TableDelta missingDelete = new Transactions.TableDelta();
        missingDelete.deletes().add(new Common.RowId(100));
        Transactions.TableDelta doubleDelete = new Transactions.TableDelta();
        doubleDelete.deletes().add(rowId);

        assertAll(
                () -> assertThrows(Common.DatabaseException.class,
                        () -> Storage.applyCommit(committed.snapshot(), Map.of(tableId, missingUpdate), 5)),
                () -> assertThrows(Common.DatabaseException.class,
                        () -> Storage.applyCommit(deletedSnapshot, Map.of(tableId, deletedUpdate), 5)),
                () -> assertThrows(Common.DatabaseException.class,
                        () -> Storage.applyCommit(committed.snapshot(), Map.of(tableId, missingDelete), 5)),
                () -> assertThrows(Common.DatabaseException.class,
                        () -> Storage.applyCommit(deletedSnapshot, Map.of(tableId, doubleDelete), 5))
        );
    }

    @Test
    void writesAndReadsSnapshotsWithNullValuesAndVersionChains() {
        Common.ObjectId tableId = new Common.ObjectId(5);
        Common.RowId firstRow = new Common.RowId(1);
        Common.RowId secondRow = new Common.RowId(2);

        LinkedHashMap<Common.RowId, List<Storage.RowVersion>> chains = new LinkedHashMap<>();
        chains.put(firstRow, List.of(
                new Storage.RowVersion(firstRow, 1, 3,
                        List.of(Common.Value.text("draft"), Common.Value.nullValue(Common.DataType.INTEGER))),
                new Storage.RowVersion(firstRow, 3, 0,
                        List.of(Common.Value.text("final"), Common.Value.integer(9)))
        ));
        chains.put(secondRow, List.of(
                new Storage.RowVersion(secondRow, 2, 0,
                        List.of(Common.Value.bool(true), Common.Value.text("note|comma,ok")))
        ));

        Storage.StorageSnapshot snapshot = new Storage.StorageSnapshot(Map.of(
                tableId, new Storage.TableData(4, chains)
        ));

        Storage.writeSnapshots(tempDir, snapshot);
        Storage.StorageSnapshot restored = Storage.readSnapshots(tempDir);

        assertAll(
                () -> assertEquals(snapshot, restored),
                () -> assertEquals(List.of(Common.Value.text("draft"), Common.Value.nullValue(Common.DataType.INTEGER)),
                        Storage.currentRow(restored, tableId, firstRow, 2)),
                () -> assertEquals(List.of(Common.Value.text("final"), Common.Value.integer(9)),
                        Storage.currentRow(restored, tableId, firstRow, 3)),
                () -> assertEquals(List.of(Common.Value.bool(true), Common.Value.text("note|comma,ok")),
                        Storage.currentRow(restored, tableId, secondRow, 3))
        );
    }

    @Test
    void writesAndReadsSnapshotsWithTypedValues() {
        Common.ObjectId tableId = new Common.ObjectId(6);
        Common.RowId rowId = new Common.RowId(1);
        Storage.StorageSnapshot snapshot = new Storage.StorageSnapshot(Map.of(
                tableId, new Storage.TableData(2, Map.of(
                        rowId, List.of(new Storage.RowVersion(rowId, 5, 0, List.of(
                                Common.Value.decimal(new BigDecimal("12.50")),
                                Common.Value.date(LocalDate.parse("2026-04-19")),
                                Common.Value.time(LocalTime.parse("10:15:30")),
                                Common.Value.timestamp(LocalDateTime.parse("2026-04-19T10:15:30")),
                                Common.Value.nullValue(Common.DataType.TEXT)
                        )))
                ))
        ));

        Storage.writeSnapshots(tempDir, snapshot);
        Storage.StorageSnapshot restored = Storage.readSnapshots(tempDir);

        assertAll(
                () -> assertEquals(0, new BigDecimal("12.50").compareTo(
                        Storage.currentRow(restored, tableId, rowId, Long.MAX_VALUE).get(0).asDecimal())),
                () -> assertEquals(LocalDate.parse("2026-04-19"),
                        Storage.currentRow(restored, tableId, rowId, Long.MAX_VALUE).get(1).asDate()),
                () -> assertEquals(LocalTime.parse("10:15:30"),
                        Storage.currentRow(restored, tableId, rowId, Long.MAX_VALUE).get(2).asTime()),
                () -> assertEquals(LocalDateTime.parse("2026-04-19T10:15:30"),
                        Storage.currentRow(restored, tableId, rowId, Long.MAX_VALUE).get(3).asTimestamp()),
                () -> assertTrue(Storage.currentRow(restored, tableId, rowId, Long.MAX_VALUE).get(4).isNull())
        );
    }

    @Test
    void writeSnapshotsReplacesStaleTableFiles() {
        Common.ObjectId firstTable = new Common.ObjectId(1);
        Common.ObjectId secondTable = new Common.ObjectId(2);

        Storage.StorageSnapshot initial = new Storage.StorageSnapshot(Map.of(
                firstTable, new Storage.TableData(2, Map.of(
                        new Common.RowId(1), List.of(new Storage.RowVersion(new Common.RowId(1), 1, 0,
                                List.of(Common.Value.text("stale"))))
                )),
                secondTable, new Storage.TableData(2, Map.of(
                        new Common.RowId(1), List.of(new Storage.RowVersion(new Common.RowId(1), 1, 0,
                                List.of(Common.Value.integer(1))))
                ))
        ));
        Storage.StorageSnapshot replacement = new Storage.StorageSnapshot(Map.of(
                secondTable, new Storage.TableData(3, Map.of(
                        new Common.RowId(2), List.of(new Storage.RowVersion(new Common.RowId(2), 2, 0,
                                List.of(Common.Value.integer(2), Common.Value.nullValue(Common.DataType.TEXT))))
                ))
        ));

        Storage.writeSnapshots(tempDir, initial);
        Storage.writeSnapshots(tempDir, replacement);

        assertAll(
                () -> assertFalse(Files.exists(tempDir.resolve("table-1.tbl"))),
                () -> assertTrue(Files.exists(tempDir.resolve("table-2.tbl"))),
                () -> assertEquals(replacement, Storage.readSnapshots(tempDir))
        );
    }

    @Test
    void writesBinaryPagedSnapshotsAndRestoresAcrossMultiplePages() throws Exception {
        Common.ObjectId tableId = new Common.ObjectId(9);
        LinkedHashMap<Common.RowId, List<Storage.RowVersion>> chains = new LinkedHashMap<>();
        for (int row = 1; row <= 180; row++) {
            Common.RowId rowId = new Common.RowId(row);
            chains.put(rowId, List.of(new Storage.RowVersion(rowId, row, 0,
                    List.of(
                            Common.Value.integer(row),
                            Common.Value.text("payload-" + row + "-" + "x".repeat(80))
                    ))));
        }
        Storage.StorageSnapshot snapshot = new Storage.StorageSnapshot(Map.of(tableId, new Storage.TableData(181, chains)));

        Storage.writeSnapshots(tempDir, snapshot);
        Path file = tempDir.resolve("table-9.tbl");
        byte[] bytes = Files.readAllBytes(file);
        Storage.StorageSnapshot restored = Storage.readSnapshots(tempDir);

        assertAll(
                () -> assertTrue(bytes.length > 64 + PagedTableStorage.PAGE_SIZE),
                () -> assertEquals(0x4A445442, java.nio.ByteBuffer.wrap(bytes, 0, Integer.BYTES).getInt()),
                () -> assertEquals(snapshot, restored),
                () -> assertEquals(List.of(Common.Value.integer(180), Common.Value.text("payload-180-" + "x".repeat(80))),
                        Storage.currentRow(restored, tableId, new Common.RowId(180), Long.MAX_VALUE))
        );
    }

    @Test
    void detectsCorruptedBinaryPageChecksums() throws Exception {
        Common.ObjectId tableId = new Common.ObjectId(11);
        Storage.StorageSnapshot snapshot = new Storage.StorageSnapshot(Map.of(
                tableId, new Storage.TableData(2, Map.of(
                        new Common.RowId(1), List.of(new Storage.RowVersion(new Common.RowId(1), 1, 0,
                                List.of(Common.Value.integer(1), Common.Value.text("stable"))))
                ))
        ));

        Storage.writeSnapshots(tempDir, snapshot);
        Path file = tempDir.resolve("table-11.tbl");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        Common.DatabaseException error = assertThrows(Common.DatabaseException.class, () -> Storage.readSnapshots(tempDir));
        assertEquals(Common.ErrorCode.STORAGE_ERROR, error.code());
    }

    @Test
    void readsLegacyTextSnapshotFilesForCompatibility() throws Exception {
        Files.write(tempDir.resolve("table-7.tbl"), List.of(
                "TABLE|1|7|3",
                "ROW|1|1|0|" + Common.Values.encodeValue(Common.Value.integer(42)) + "," + Common.Values.encodeValue(Common.Value.text("legacy")),
                "ROW|2|2|0|" + Common.Values.encodeValue(Common.Value.bool(true))
        ));

        Storage.StorageSnapshot restored = Storage.readSnapshots(tempDir);

        assertAll(
                () -> assertEquals(List.of(Common.Value.integer(42), Common.Value.text("legacy")),
                        Storage.currentRow(restored, new Common.ObjectId(7), new Common.RowId(1), 5)),
                () -> assertEquals(List.of(Common.Value.bool(true)),
                        Storage.currentRow(restored, new Common.ObjectId(7), new Common.RowId(2), 5))
        );
    }
}
