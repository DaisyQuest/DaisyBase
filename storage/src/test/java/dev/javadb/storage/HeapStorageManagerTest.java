package dev.javadb.storage;

import dev.javadb.common.Common;
import dev.javadb.txn.Transactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapStorageManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsOverflowRowsAcrossEvictionReloadAndSubsequentUpdates() {
        Common.ObjectId tableId = new Common.ObjectId(21);
        try (HeapStorageManager manager = new HeapStorageManager(tempDir, 3)) {
            Transactions.TableDelta initial = new Transactions.TableDelta();
            LinkedHashMap<Common.RowId, Common.RowId> inserted = new LinkedHashMap<>();
            for (int row = 1; row <= 180; row++) {
                Common.RowId tempRowId = new Common.RowId(-row);
                initial.inserts().put(tempRowId, List.of(
                        Common.Value.integer(row),
                        Common.Value.text(row == 1 ? "X".repeat(50_000) : "payload-" + row + "-" + "y".repeat(90))
                ));
                inserted.put(tempRowId, new Common.RowId(row));
            }

            HeapStorageManager.CommitResult initialCommit = manager.applyCommit(
                    Map.of(tableId, initial),
                    Map.of(tableId, inserted),
                    7);
            assertTrue(initialCommit.pageImages().size() > 1);
            assertTrue(manager.cachedPageCount() <= manager.maxCachedPages());
            manager.flushDirtyPages();

            Transactions.TableDelta second = new Transactions.TableDelta();
            second.updates().put(new Common.RowId(1), List.of(
                    Common.Value.integer(1),
                    Common.Value.text("updated-" + "Z".repeat(35_000))
            ));
            second.deletes().add(new Common.RowId(2));

            HeapStorageManager.CommitResult secondCommit = manager.applyCommit(
                    Map.of(tableId, second),
                    Map.of(),
                    8);
            assertTrue(secondCommit.pageImages().stream().anyMatch(image -> image.tableId().equals(tableId)));
            manager.flushDirtyPages();
        }

        try (HeapStorageManager reopened = new HeapStorageManager(tempDir, 3)) {
            Storage.StorageSnapshot snapshot = reopened.loadSnapshot();
            assertEquals("updated-" + "Z".repeat(35_000),
                    Storage.currentRow(snapshot, tableId, new Common.RowId(1), Long.MAX_VALUE).get(1).asText());
            assertEquals(null, Storage.currentRow(snapshot, tableId, new Common.RowId(2), Long.MAX_VALUE));
            assertEquals("payload-180-" + "y".repeat(90),
                    Storage.currentRow(snapshot, tableId, new Common.RowId(180), Long.MAX_VALUE).get(1).asText());
        }
    }

    @Test
    void replaysCapturedPageImagesIntoFreshStorage() {
        Common.ObjectId tableId = new Common.ObjectId(30);
        List<Storage.PageImage> pageImages;
        Path sourceDir = tempDir.resolve("source");
        Path recoveredDir = tempDir.resolve("recovered");

        try (HeapStorageManager source = new HeapStorageManager(sourceDir, 4)) {
            Transactions.TableDelta delta = new Transactions.TableDelta();
            Common.RowId tempRowId = new Common.RowId(-1);
            delta.inserts().put(tempRowId, List.of(
                    Common.Value.integer(99),
                    Common.Value.text("recover-" + "Q".repeat(20_000))
            ));
            pageImages = source.applyCommit(
                    Map.of(tableId, delta),
                    Map.of(tableId, Map.of(tempRowId, new Common.RowId(1))),
                    11).pageImages();
        }

        try (HeapStorageManager recovered = new HeapStorageManager(recoveredDir, 4)) {
            recovered.applyRecoveredPages(assignLsns(pageImages, 100));
            Storage.StorageSnapshot snapshot = recovered.loadSnapshot();
            assertEquals(List.of(Common.Value.integer(99), Common.Value.text("recover-" + "Q".repeat(20_000))),
                    Storage.currentRow(snapshot, tableId, new Common.RowId(1), Long.MAX_VALUE));
        }
    }

    @Test
    void skipsStaleRecoveredPageImagesUsingPersistedPageLsns() {
        Common.ObjectId tableId = new Common.ObjectId(31);
        Path sourceDir = tempDir.resolve("stale-source");
        Path targetDir = tempDir.resolve("stale-target");
        List<Storage.PageImage> firstImages;
        List<Storage.PageImage> secondImages;

        try (HeapStorageManager source = new HeapStorageManager(sourceDir, 4)) {
            Transactions.TableDelta first = new Transactions.TableDelta();
            Common.RowId tempRowId = new Common.RowId(-1);
            first.inserts().put(tempRowId, List.of(Common.Value.integer(1), Common.Value.text("first-version")));
            firstImages = assignLsns(source.applyCommit(
                    Map.of(tableId, first),
                    Map.of(tableId, Map.of(tempRowId, new Common.RowId(1))),
                    20).pageImages(), 200);
            source.flushDirtyPages();

            Transactions.TableDelta second = new Transactions.TableDelta();
            second.updates().put(new Common.RowId(1), List.of(Common.Value.integer(1), Common.Value.text("second-version")));
            secondImages = assignLsns(source.applyCommit(
                    Map.of(tableId, second),
                    Map.of(),
                    21).pageImages(), 300);
        }

        try (HeapStorageManager target = new HeapStorageManager(targetDir, 4)) {
            target.applyRecoveredPages(secondImages);
        }
        try (HeapStorageManager reopened = new HeapStorageManager(targetDir, 4)) {
            reopened.applyRecoveredPages(firstImages);
            Storage.StorageSnapshot snapshot = reopened.loadSnapshot();
            assertEquals("second-version",
                    Storage.currentRow(snapshot, tableId, new Common.RowId(1), Long.MAX_VALUE).get(1).asText());
        }
    }

    @Test
    void bootstrapsFromSnapshotDetectsLiveFormatAndDiscardsUnflushedChanges() {
        Common.ObjectId tableId = new Common.ObjectId(41);
        Common.RowId rowId = new Common.RowId(1);
        Storage.StorageSnapshot snapshot = new Storage.StorageSnapshot(Map.of(
                tableId, new Storage.TableData(2, Map.of(
                        rowId, List.of(new Storage.RowVersion(rowId, 5, 0,
                                List.of(Common.Value.integer(1), Common.Value.text("seed"))))
                ))
        ));

        try (HeapStorageManager manager = new HeapStorageManager(tempDir, 4)) {
            manager.bootstrapFromSnapshot(snapshot);
            assertTrue(manager.hasLiveFormat());
            assertEquals(snapshot, manager.loadSnapshot());

            Transactions.TableDelta update = new Transactions.TableDelta();
            update.updates().put(rowId, List.of(Common.Value.integer(2), Common.Value.text("dirty")));
            HeapStorageManager.CommitResult commit = manager.applyCommit(Map.of(tableId, update), Map.of(), 6);
            assertEquals("dirty", Storage.currentRow(manager.loadSnapshot(), tableId, rowId, Long.MAX_VALUE).get(1).asText());

            manager.discardUnflushedChanges(commit.changedTables());
            Storage.StorageSnapshot reverted = manager.loadSnapshot();
            assertEquals("seed", Storage.currentRow(reverted, tableId, rowId, Long.MAX_VALUE).get(1).asText());
        }
    }

    @Test
    void persistsTypedValuesAndNullTransitionsInLiveStorage() {
        Common.ObjectId tableId = new Common.ObjectId(42);
        try (HeapStorageManager manager = new HeapStorageManager(tempDir, 4)) {
            Transactions.TableDelta insert = new Transactions.TableDelta();
            Common.RowId tempRowId = new Common.RowId(-1);
            insert.inserts().put(tempRowId, List.of(
                    Common.Value.decimal(new BigDecimal("12.50")),
                    Common.Value.date(LocalDate.parse("2026-04-19")),
                    Common.Value.time(LocalTime.parse("10:15:30")),
                    Common.Value.timestamp(LocalDateTime.parse("2026-04-19T10:15:30")),
                    Common.Value.nullValue(Common.DataType.TEXT)
            ));
            manager.applyCommit(Map.of(tableId, insert), Map.of(tableId, Map.of(tempRowId, new Common.RowId(1))), 12);
            manager.flushDirtyPages();

            Transactions.TableDelta update = new Transactions.TableDelta();
            update.updates().put(new Common.RowId(1), List.of(
                    Common.Value.decimal(new BigDecimal("15.75")),
                    Common.Value.date(LocalDate.parse("2026-04-20")),
                    Common.Value.time(LocalTime.parse("11:45:00")),
                    Common.Value.timestamp(LocalDateTime.parse("2026-04-20T11:45:00")),
                    Common.Value.text("typed")
            ));
            manager.applyCommit(Map.of(tableId, update), Map.of(), 13);
            manager.flushDirtyPages();
        }

        try (HeapStorageManager reopened = new HeapStorageManager(tempDir, 4)) {
            List<Common.Value> row = Storage.currentRow(reopened.loadSnapshot(), tableId, new Common.RowId(1), Long.MAX_VALUE);
            assertEquals(0, new BigDecimal("15.75").compareTo(row.get(0).asDecimal()));
            assertEquals(LocalDate.parse("2026-04-20"), row.get(1).asDate());
            assertEquals(LocalTime.parse("11:45:00"), row.get(2).asTime());
            assertEquals(LocalDateTime.parse("2026-04-20T11:45:00"), row.get(3).asTimestamp());
            assertEquals("typed", row.get(4).asText());
        }
    }

    private List<Storage.PageImage> assignLsns(List<Storage.PageImage> pageImages, long firstLsn) {
        AtomicLong next = new AtomicLong(firstLsn);
        return pageImages.stream()
                .map(image -> new Storage.PageImage(image.tableId(), image.pageNumber(), next.getAndIncrement(), image.bytes()))
                .toList();
    }
}
