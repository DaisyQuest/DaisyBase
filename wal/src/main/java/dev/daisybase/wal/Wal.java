package dev.daisybase.wal;

import dev.daisybase.catalog.Catalog;
import dev.daisybase.common.Common;
import dev.daisybase.storage.Storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Wal {
    private Wal() {
    }

    public record MutationRecord(String kind, Common.ObjectId tableId, Common.RowId rowId, List<Common.Value> values) {
        public MutationRecord {
            values = List.copyOf(values);
        }

        public String serialize() {
            String payload = values.stream().map(Common.Values::encodeValue).reduce((left, right) -> left + "," + right).orElse("");
            return kind + "|" + tableId.value() + "|" + rowId.value() + "|" + payload;
        }

        public static MutationRecord deserialize(String text) {
            String[] parts = text.split("\\|", 4);
            List<Common.Value> values = parts.length < 4 || parts[3].isBlank()
                    ? List.of()
                    : java.util.Arrays.stream(parts[3].split(",")).map(Common.Values::decodeValue).toList();
            return new MutationRecord(parts[0], new Common.ObjectId(Long.parseLong(parts[1])), new Common.RowId(Long.parseLong(parts[2])), values);
        }
    }

    public record RecoveredTransaction(Common.TransactionId transactionId, long commitSequence,
                                       List<Catalog.CatalogChange> catalogChanges,
                                       List<MutationRecord> mutationRecords,
                                       List<Storage.PageImage> pageImages) {
    }

    public record Meta(long nextLsn, long lastCommitSequence, long nextObjectId) {
    }

    public record AppendResult(long commitLsn, List<Storage.PageImage> pageImages) {
        public AppendResult {
            pageImages = List.copyOf(pageImages);
        }
    }

    public static final class WalManager {
        private final Path walDir;
        private final Path walFile;
        private final Path metaFile;
        private Meta meta;

        public WalManager(Path walDir) {
            this.walDir = walDir;
            this.walFile = walDir.resolve("wal.log");
            this.metaFile = walDir.resolve("wal.meta");
            this.meta = loadMeta();
        }

        public synchronized Meta meta() {
            return meta;
        }

        public synchronized void updateNextObjectId(long nextObjectId) {
            meta = new Meta(meta.nextLsn(), meta.lastCommitSequence(), nextObjectId);
            persistMeta();
        }

        public synchronized AppendResult appendCommittedTransaction(Common.TransactionId transactionId,
                                                                    List<Catalog.CatalogChange> catalogChanges,
                                                                    List<MutationRecord> mutations,
                                                                    List<Storage.PageImage> pageImages,
                                                                    long commitSequence,
                                                                    boolean force) {
            try {
                Files.createDirectories(walDir);
                List<String> lines = new ArrayList<>();
                long lsn = meta.nextLsn();
                List<Storage.PageImage> assignedPageImages = new ArrayList<>();
                for (Catalog.CatalogChange change : catalogChanges) {
                    lines.add(lsn++ + "|CATALOG|" + transactionId.value() + "|" + Common.Values.encodeString(change.serialize()));
                }
                for (MutationRecord mutation : mutations) {
                    lines.add(lsn++ + "|MUTATION|" + transactionId.value() + "|" + Common.Values.encodeString(mutation.serialize()));
                }
                for (Storage.PageImage pageImage : pageImages) {
                    long pageLsn = lsn++;
                    String payload = pageImage.tableId().value() + "|" + pageImage.pageNumber() + "|"
                            + Common.Values.encodeString(new String(pageImage.bytes(), StandardCharsets.ISO_8859_1));
                    lines.add(pageLsn + "|PAGE|" + transactionId.value() + "|" + Common.Values.encodeString(payload));
                    assignedPageImages.add(new Storage.PageImage(pageImage.tableId(), pageImage.pageNumber(), pageLsn, pageImage.bytes()));
                }
                long commitLsn = lsn;
                lines.add(commitLsn + "|COMMIT|" + transactionId.value() + "|" + commitSequence);
                try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer buffer = StandardCharsets.UTF_8.encode(String.join(System.lineSeparator(), lines) + System.lineSeparator());
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    if (force) {
                        channel.force(true);
                    }
                }
                meta = new Meta(commitLsn + 1, commitSequence, meta.nextObjectId());
                persistMeta();
                return new AppendResult(commitLsn, assignedPageImages);
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to append WAL", exception);
            }
        }

        public synchronized AppendResult appendCommittedTransaction(Common.TransactionId transactionId,
                                                                   List<Catalog.CatalogChange> catalogChanges,
                                                                   List<MutationRecord> mutations,
                                                                   long commitSequence,
                                                                   boolean force) {
            return appendCommittedTransaction(transactionId, catalogChanges, mutations, List.of(), commitSequence, force);
        }

        public synchronized List<RecoveredTransaction> recover() {
            if (!Files.exists(walFile)) {
                return List.of();
            }
            try {
                Map<Long, List<Catalog.CatalogChange>> catalogChanges = new LinkedHashMap<>();
                Map<Long, List<MutationRecord>> mutations = new LinkedHashMap<>();
                Map<Long, List<Storage.PageImage>> pageImages = new LinkedHashMap<>();
                List<RecoveredTransaction> recovered = new ArrayList<>();
                for (String line : Files.readAllLines(walFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\\|", 4);
                    long recordLsn = Long.parseLong(parts[0]);
                    long txId = Long.parseLong(parts[2]);
                    switch (parts[1]) {
                        case "CATALOG" -> catalogChanges.computeIfAbsent(txId, ignored -> new ArrayList<>())
                                .add(Catalog.deserializeChange(Common.Values.decodeString(parts[3])));
                        case "MUTATION" -> mutations.computeIfAbsent(txId, ignored -> new ArrayList<>())
                                .add(MutationRecord.deserialize(Common.Values.decodeString(parts[3])));
                        case "PAGE" -> {
                            String[] payload = Common.Values.decodeString(parts[3]).split("\\|", 3);
                            pageImages.computeIfAbsent(txId, ignored -> new ArrayList<>())
                                    .add(new Storage.PageImage(
                                            new Common.ObjectId(Long.parseLong(payload[0])),
                                            Integer.parseInt(payload[1]),
                                            recordLsn,
                                            Common.Values.decodeString(payload[2]).getBytes(StandardCharsets.ISO_8859_1)));
                        }
                        case "COMMIT" -> recovered.add(new RecoveredTransaction(new Common.TransactionId(txId), Long.parseLong(parts[3]),
                                List.copyOf(catalogChanges.getOrDefault(txId, List.of())),
                                List.copyOf(mutations.getOrDefault(txId, List.of())),
                                List.copyOf(pageImages.getOrDefault(txId, List.of()))));
                        default -> throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Unknown WAL record: " + line);
                    }
                }
                return recovered;
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to recover WAL", exception);
            }
        }

        public synchronized void checkpoint(long lastCommitSequence, long nextObjectId) {
            try {
                Files.createDirectories(walDir);
                Files.writeString(walFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                meta = new Meta(meta.nextLsn(), lastCommitSequence, nextObjectId);
                persistMeta();
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to checkpoint WAL", exception);
            }
        }

        private Meta loadMeta() {
            try {
                Files.createDirectories(walDir);
                if (!Files.exists(metaFile)) {
                    Meta fresh = new Meta(1, 0, 2);
                    Files.write(metaFile, List.of("META|1|1|0|2"), StandardCharsets.UTF_8);
                    return fresh;
                }
                List<String> lines = Files.readAllLines(metaFile, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    return new Meta(1, 0, 2);
                }
                String[] parts = lines.getFirst().split("\\|");
                return new Meta(Long.parseLong(parts[2]), Long.parseLong(parts[3]), Long.parseLong(parts[4]));
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to load WAL metadata", exception);
            }
        }

        private void persistMeta() {
            try {
                Files.createDirectories(walDir);
                Files.write(metaFile, List.of("META|1|" + meta.nextLsn() + "|" + meta.lastCommitSequence() + "|" + meta.nextObjectId()),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to persist WAL metadata", exception);
            }
        }
    }
}
