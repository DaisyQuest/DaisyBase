package dev.daisybase.storage;

import dev.daisybase.common.Common;
import dev.daisybase.txn.Transactions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Storage {
    public static final int FILE_HEADER_PAGE_NUMBER = -1;

    private Storage() {
    }

    public record RowVersion(Common.RowId rowId, long createdAtCommitSequence, long deletedAtCommitSequence,
                             List<Common.Value> values) {
        public RowVersion {
            values = List.copyOf(values);
        }

        public boolean visibleAt(long snapshotSequence) {
            return createdAtCommitSequence <= snapshotSequence
                    && (deletedAtCommitSequence == 0 || deletedAtCommitSequence > snapshotSequence);
        }
    }

    public record TableData(long nextRowId, Map<Common.RowId, List<RowVersion>> versionChains) {
        public TableData {
            versionChains = Map.copyOf(versionChains);
        }
    }

    public record StorageSnapshot(Map<Common.ObjectId, TableData> tables) {
        public StorageSnapshot {
            tables = Map.copyOf(tables);
        }
    }

    public record VisibleRow(Common.RowId rowId, List<Common.Value> values) {
        public VisibleRow {
            values = List.copyOf(values);
        }
    }

    public record AppliedCommit(StorageSnapshot snapshot, Map<Common.ObjectId, Map<Common.RowId, Common.RowId>> insertedRowMappings) {
    }

    public record RecoveredMutation(String kind, Common.ObjectId tableId, Common.RowId rowId, List<Common.Value> values) {
        public RecoveredMutation {
            values = List.copyOf(values);
        }
    }

    public record PageImage(Common.ObjectId tableId, int pageNumber, long lsn, byte[] bytes) {
        public PageImage {
            bytes = bytes.clone();
        }
    }

    public static StorageSnapshot empty() {
        return new StorageSnapshot(Map.of());
    }

    public static StorageSnapshot ensureTable(StorageSnapshot snapshot, Common.ObjectId tableId) {
        if (snapshot.tables().containsKey(tableId)) {
            return snapshot;
        }
        LinkedHashMap<Common.ObjectId, TableData> tables = new LinkedHashMap<>(snapshot.tables());
        tables.put(tableId, new TableData(1, Map.of()));
        return new StorageSnapshot(tables);
    }

    public static List<VisibleRow> visibleRows(StorageSnapshot snapshot, Common.ObjectId tableId, long snapshotSequence,
                                               Transactions.TableDelta delta) {
        LinkedHashMap<Common.RowId, List<Common.Value>> visible = new LinkedHashMap<>();
        TableData tableData = snapshot.tables().get(tableId);
        if (tableData != null) {
            tableData.versionChains().values().stream()
                    .map(chain -> currentVisible(chain, snapshotSequence))
                    .filter(Objects::nonNull)
                    .forEach(version -> visible.put(version.rowId(), version.values()));
        }
        if (delta != null) {
            delta.updates().forEach(visible::put);
            delta.deletes().forEach(visible::remove);
            delta.inserts().forEach(visible::put);
        }
        return visible.entrySet().stream().map(entry -> new VisibleRow(entry.getKey(), entry.getValue())).toList();
    }

    public static RowVersion currentVisible(List<RowVersion> chain, long snapshotSequence) {
        RowVersion visible = null;
        for (RowVersion version : chain) {
            if (version.visibleAt(snapshotSequence)) {
                visible = version;
            }
        }
        return visible;
    }

    public static boolean rowExists(StorageSnapshot snapshot, Common.ObjectId tableId, Common.RowId rowId) {
        TableData tableData = snapshot.tables().get(tableId);
        if (tableData == null) {
            return false;
        }
        List<RowVersion> chain = tableData.versionChains().get(rowId);
        return chain != null && chain.stream().anyMatch(version -> version.deletedAtCommitSequence() == 0);
    }

    public static List<Common.Value> currentRow(StorageSnapshot snapshot, Common.ObjectId tableId, Common.RowId rowId, long snapshotSequence) {
        TableData tableData = snapshot.tables().get(tableId);
        if (tableData == null) {
            return null;
        }
        List<RowVersion> chain = tableData.versionChains().get(rowId);
        if (chain == null) {
            return null;
        }
        RowVersion visible = currentVisible(chain, snapshotSequence);
        return visible == null ? null : visible.values();
    }

    public static AppliedCommit applyCommit(StorageSnapshot base, Map<Common.ObjectId, Transactions.TableDelta> deltas,
                                            long commitSequence) {
        LinkedHashMap<Common.ObjectId, TableData> tables = new LinkedHashMap<>(base.tables());
        LinkedHashMap<Common.ObjectId, Map<Common.RowId, Common.RowId>> insertedMappings = new LinkedHashMap<>();
        for (Map.Entry<Common.ObjectId, Transactions.TableDelta> entry : deltas.entrySet()) {
            Common.ObjectId tableId = entry.getKey();
            Transactions.TableDelta delta = entry.getValue();
            TableData original = tables.getOrDefault(tableId, new TableData(1, Map.of()));
            long nextRowId = original.nextRowId();
            LinkedHashMap<Common.RowId, List<RowVersion>> chains = deepCopy(original.versionChains());
            LinkedHashMap<Common.RowId, Common.RowId> mapping = new LinkedHashMap<>();

            for (Map.Entry<Common.RowId, List<Common.Value>> insert : delta.inserts().entrySet()) {
                Common.RowId actualRowId = new Common.RowId(nextRowId++);
                mapping.put(insert.getKey(), actualRowId);
                chains.put(actualRowId, List.of(new RowVersion(actualRowId, commitSequence, 0, insert.getValue())));
            }

            for (Map.Entry<Common.RowId, List<Common.Value>> update : delta.updates().entrySet()) {
                Common.RowId rowId = update.getKey();
                List<RowVersion> chain = new ArrayList<>(chains.getOrDefault(rowId, List.of()));
                if (chain.isEmpty()) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT, "Missing row for update: " + rowId.value());
                }
                RowVersion latest = chain.get(chain.size() - 1);
                if (latest.deletedAtCommitSequence() != 0) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT, "Row already deleted: " + rowId.value());
                }
                chain.set(chain.size() - 1, new RowVersion(latest.rowId(), latest.createdAtCommitSequence(), commitSequence, latest.values()));
                chain.add(new RowVersion(rowId, commitSequence, 0, update.getValue()));
                chains.put(rowId, List.copyOf(chain));
            }

            for (Common.RowId delete : delta.deletes()) {
                List<RowVersion> chain = new ArrayList<>(chains.getOrDefault(delete, List.of()));
                if (chain.isEmpty()) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT, "Missing row for delete: " + delete.value());
                }
                RowVersion latest = chain.get(chain.size() - 1);
                if (latest.deletedAtCommitSequence() != 0) {
                    throw new Common.DatabaseException(Common.ErrorCode.TRANSACTION_CONFLICT, "Row already deleted: " + delete.value());
                }
                chain.set(chain.size() - 1, new RowVersion(latest.rowId(), latest.createdAtCommitSequence(), commitSequence, latest.values()));
                chains.put(delete, List.copyOf(chain));
            }

            tables.put(tableId, new TableData(nextRowId, chains));
            insertedMappings.put(tableId, mapping);
        }
        return new AppliedCommit(new StorageSnapshot(tables), insertedMappings);
    }

    public static StorageSnapshot applyRecoveredMutations(StorageSnapshot base, List<RecoveredMutation> mutations, long commitSequence) {
        LinkedHashMap<Common.ObjectId, TableData> tables = new LinkedHashMap<>(base.tables());
        for (RecoveredMutation mutation : mutations) {
            TableData original = tables.getOrDefault(mutation.tableId(), new TableData(1, Map.of()));
            long nextRowId = original.nextRowId();
            LinkedHashMap<Common.RowId, List<RowVersion>> chains = deepCopy(original.versionChains());
            switch (mutation.kind().toUpperCase()) {
                case "INSERT" -> {
                    chains.put(mutation.rowId(), List.of(new RowVersion(mutation.rowId(), commitSequence, 0, mutation.values())));
                    nextRowId = Math.max(nextRowId, mutation.rowId().value() + 1);
                }
                case "UPDATE" -> {
                    List<RowVersion> chain = new ArrayList<>(chains.getOrDefault(mutation.rowId(), List.of()));
                    if (chain.isEmpty()) {
                        throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Missing row during recovery update: " + mutation.rowId().value());
                    }
                    RowVersion latest = chain.get(chain.size() - 1);
                    chain.set(chain.size() - 1, new RowVersion(latest.rowId(), latest.createdAtCommitSequence(), commitSequence, latest.values()));
                    chain.add(new RowVersion(mutation.rowId(), commitSequence, 0, mutation.values()));
                    chains.put(mutation.rowId(), List.copyOf(chain));
                }
                case "DELETE" -> {
                    List<RowVersion> chain = new ArrayList<>(chains.getOrDefault(mutation.rowId(), List.of()));
                    if (chain.isEmpty()) {
                        throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Missing row during recovery delete: " + mutation.rowId().value());
                    }
                    RowVersion latest = chain.get(chain.size() - 1);
                    chain.set(chain.size() - 1, new RowVersion(latest.rowId(), latest.createdAtCommitSequence(), commitSequence, latest.values()));
                    chains.put(mutation.rowId(), List.copyOf(chain));
                }
                default -> throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Unknown recovery mutation " + mutation.kind());
            }
            tables.put(mutation.tableId(), new TableData(nextRowId, chains));
        }
        return new StorageSnapshot(tables);
    }

    public static void writeSnapshots(Path dataDir, StorageSnapshot snapshot) {
        try {
            Files.createDirectories(dataDir);
            if (Files.exists(dataDir)) {
                try (var stream = Files.list(dataDir)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".tbl"))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException exception) {
                                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                                            "Failed to clear old table snapshot", exception);
                                }
                            });
                }
            }
            for (Map.Entry<Common.ObjectId, TableData> entry : snapshot.tables().entrySet()) {
                Path tablePath = dataDir.resolve("table-" + entry.getKey().value() + ".tbl");
                PagedTableStorage.writeTable(tablePath, entry.getKey(), entry.getValue());
            }
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to write table snapshots", exception);
        }
    }

    public static StorageSnapshot readSnapshots(Path dataDir) {
        if (!Files.exists(dataDir)) {
            return empty();
        }
        try {
            LinkedHashMap<Common.ObjectId, TableData> tables = new LinkedHashMap<>();
            try (var stream = Files.list(dataDir)) {
                for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".tbl")).toList()) {
                    Common.ObjectId tableId = tableIdFromFile(file);
                    if (PagedTableStorage.isBinaryTableFile(file)) {
                        tables.put(tableId, PagedTableStorage.readTable(file, tableId));
                    } else {
                        TableData legacy = readLegacyTable(file);
                        tables.put(tableId, legacy);
                    }
                }
            }
            return new StorageSnapshot(tables);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to read table snapshots", exception);
        }
    }

    private static Common.ObjectId tableIdFromFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("table-") || !name.endsWith(".tbl")) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Unexpected table file name: " + name);
        }
        return new Common.ObjectId(Long.parseLong(name.substring("table-".length(), name.length() - ".tbl".length())));
    }

    private static TableData readLegacyTable(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long nextRowId = 1;
        LinkedHashMap<Common.RowId, List<RowVersion>> chains = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 5);
            if (parts[0].equals("TABLE")) {
                nextRowId = Long.parseLong(parts[3]);
            } else if (parts[0].equals("ROW")) {
                Common.RowId rowId = new Common.RowId(Long.parseLong(parts[1]));
                long created = Long.parseLong(parts[2]);
                long deleted = Long.parseLong(parts[3]);
                List<Common.Value> values = parts.length < 5 || parts[4].isBlank()
                        ? List.of()
                        : Arrays.stream(parts[4].split(",")).map(Common.Values::decodeValue).toList();
                chains.computeIfAbsent(rowId, ignored -> new ArrayList<>()).add(new RowVersion(rowId, created, deleted, values));
            }
        }
        LinkedHashMap<Common.RowId, List<RowVersion>> ordered = new LinkedHashMap<>();
        chains.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Common.RowId::value)))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingLong(RowVersion::createdAtCommitSequence)
                                .thenComparingLong(RowVersion::deletedAtCommitSequence))
                        .toList()));
        return new TableData(nextRowId, ordered);
    }

    private static LinkedHashMap<Common.RowId, List<RowVersion>> deepCopy(Map<Common.RowId, List<RowVersion>> source) {
        LinkedHashMap<Common.RowId, List<RowVersion>> copy = new LinkedHashMap<>();
        source.forEach((rowId, versions) -> copy.put(rowId, List.copyOf(versions)));
        return copy;
    }
}
