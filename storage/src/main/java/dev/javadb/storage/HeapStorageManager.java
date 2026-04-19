package dev.javadb.storage;

import dev.javadb.common.Common;
import dev.javadb.txn.Transactions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

public final class HeapStorageManager implements AutoCloseable {
    private static final int FILE_MAGIC = 0x4A445442;
    private static final int LIVE_FILE_VERSION = 3;
    private static final int PAGE_SIZE = PagedTableStorage.PAGE_SIZE;
    private static final int FILE_HEADER_SIZE = 64;
    private static final int FILE_CHECKSUM_OFFSET = 40;
    private static final int PAGE_CHECKSUM_OFFSET = 4;
    private static final int DATA_PAGE_MAGIC = 0x50414745;
    private static final int OVERFLOW_PAGE_MAGIC = 0x4F564652;
    private static final int DATA_PAGE_HEADER_SIZE = 20;
    private static final int OVERFLOW_PAGE_HEADER_SIZE = 16;
    private static final int SLOT_ENTRY_SIZE = 4;
    private static final int INLINE_TEXT_THRESHOLD = 1024;
    private static final int MAX_RECORD_SIZE = PAGE_SIZE - DATA_PAGE_HEADER_SIZE - SLOT_ENTRY_SIZE - 128;
    private static final byte FLAG_NULL = 0x01;
    private static final byte FLAG_OVERFLOW = 0x02;
    private static final int NO_NEXT_OVERFLOW_PAGE = -1;

    private final Path dataDir;
    private final Path pageLsnPath;
    private final int maxCachedPages;
    private final LinkedHashMap<PageKey, CachedPage> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Common.ObjectId, TableState> tables = new LinkedHashMap<>();
    private final LinkedHashMap<PageKey, Long> pageLsns = new LinkedHashMap<>();
    private boolean pageLsnsDirty;

    public HeapStorageManager(Path dataDir) {
        this(dataDir, Integer.getInteger("javadb.storage.bufferPages", 64));
    }

    public HeapStorageManager(Path dataDir, int maxCachedPages) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.pageLsnPath = dataDir.resolve("page-lsns.meta");
        this.maxCachedPages = Math.max(4, maxCachedPages);
        try {
            Files.createDirectories(dataDir);
            loadPersistedPageLsns();
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to initialize data directory", exception);
        }
    }

    public synchronized boolean hasLiveFormat() {
        try (var stream = Files.list(dataDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".tbl"))
                    .anyMatch(this::isLiveTableFile);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to inspect live table files", exception);
        }
    }

    public synchronized Storage.StorageSnapshot loadSnapshot() {
        try {
            LinkedHashMap<Common.ObjectId, Storage.TableData> snapshotTables = new LinkedHashMap<>();
            try (var stream = Files.list(dataDir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".tbl"))
                        .filter(this::isLiveTableFile)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            Common.ObjectId tableId = tableIdFromPath(path);
                            TableState state = loadOrRefreshTableState(tableId);
                            snapshotTables.put(tableId, materializeTable(state));
                        });
            }
            return new Storage.StorageSnapshot(snapshotTables);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to load storage snapshot", exception);
        }
    }

    public synchronized void bootstrapFromSnapshot(Storage.StorageSnapshot snapshot) {
        try {
            clearLiveTableFiles();
            cache.clear();
            tables.clear();
            pageLsns.clear();
            pageLsnsDirty = true;
            List<Map.Entry<Common.ObjectId, Storage.TableData>> entries = snapshot.tables().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Common.ObjectId::value)))
                    .toList();
            for (Map.Entry<Common.ObjectId, Storage.TableData> entry : entries) {
                TableState state = newEmptyTableState(entry.getKey());
                entry.getValue().versionChains().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Common.RowId::value)))
                        .forEach(chain -> chain.getValue().stream()
                                .sorted(Comparator.comparingLong(Storage.RowVersion::createdAtCommitSequence)
                                        .thenComparingLong(Storage.RowVersion::deletedAtCommitSequence))
                                .forEach(version -> appendVersion(state, version)));
                state.nextRowId = Math.max(state.nextRowId, entry.getValue().nextRowId());
                state.headerDirty = true;
                tables.put(entry.getKey(), state);
            }
            flushDirtyPages();
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to bootstrap live storage", exception);
        }
    }

    public synchronized CommitResult applyCommit(Map<Common.ObjectId, Transactions.TableDelta> deltas,
                                                 Map<Common.ObjectId, Map<Common.RowId, Common.RowId>> insertedMappings,
                                                 long commitSequence) {
        LinkedHashSet<Common.ObjectId> changedTables = new LinkedHashSet<>();
        for (Map.Entry<Common.ObjectId, Transactions.TableDelta> entry : deltas.entrySet()) {
            Common.ObjectId tableId = entry.getKey();
            Transactions.TableDelta delta = entry.getValue();
            TableState state = loadOrCreateTableState(tableId);
            Map<Common.RowId, Common.RowId> insertMap = insertedMappings.getOrDefault(tableId, Map.of());

            for (Map.Entry<Common.RowId, List<Common.Value>> insert : delta.inserts().entrySet()) {
                Common.RowId actualRowId = insertMap.get(insert.getKey());
                if (actualRowId == null) {
                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Missing inserted row mapping for table " + tableId.value() + " row " + insert.getKey().value());
                }
                state.nextRowId = Math.max(state.nextRowId, actualRowId.value() + 1);
                appendVersion(state, new Storage.RowVersion(actualRowId, commitSequence, 0, insert.getValue()));
                changedTables.add(tableId);
            }
            for (Map.Entry<Common.RowId, List<Common.Value>> update : delta.updates().entrySet()) {
                markVersionDeleted(state, update.getKey(), commitSequence);
                appendVersion(state, new Storage.RowVersion(update.getKey(), commitSequence, 0, update.getValue()));
                changedTables.add(tableId);
            }
            for (Common.RowId delete : delta.deletes()) {
                markVersionDeleted(state, delete, commitSequence);
                changedTables.add(tableId);
            }
        }
        return new CommitResult(captureDirtyPageImages(changedTables), List.copyOf(changedTables));
    }

    public synchronized void discardUnflushedChanges(List<Common.ObjectId> tableIds) {
        if (tableIds.isEmpty()) {
            return;
        }
        LinkedHashSet<Common.ObjectId> targets = new LinkedHashSet<>(tableIds);
        cache.entrySet().removeIf(entry -> targets.contains(entry.getKey().tableId()));
        for (Common.ObjectId tableId : targets) {
            Path path = tablePath(tableId);
            if (Files.exists(path) && isLiveTableFile(path)) {
                tables.put(tableId, loadTableState(tableId, path));
            } else {
                tables.remove(tableId);
            }
        }
        reloadPersistedPageLsns();
    }

    public synchronized void applyRecoveredPages(List<Storage.PageImage> pageImages) {
        if (pageImages.isEmpty()) {
            return;
        }
        LinkedHashSet<Common.ObjectId> touched = new LinkedHashSet<>();
        Map<Common.ObjectId, List<Storage.PageImage>> grouped = new LinkedHashMap<>();
        for (Storage.PageImage pageImage : pageImages) {
            grouped.computeIfAbsent(pageImage.tableId(), ignored -> new ArrayList<>()).add(pageImage);
        }
        grouped.forEach((tableId, images) -> {
            Path path = tablePath(tableId);
            try {
                Files.createDirectories(dataDir);
                try (FileChannel channel = FileChannel.open(path,
                        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    for (Storage.PageImage image : images) {
                        PageKey pageKey = new PageKey(tableId, image.pageNumber());
                        long currentLsn = pageLsns.getOrDefault(pageKey, 0L);
                        if (image.lsn() > 0 && image.lsn() <= currentLsn) {
                            continue;
                        }
                        byte[] bytes = image.bytes();
                        long offset = image.pageNumber() == Storage.FILE_HEADER_PAGE_NUMBER
                                ? 0
                                : FILE_HEADER_SIZE + (long) image.pageNumber() * PAGE_SIZE;
                        channel.position(offset);
                        channel.write(ByteBuffer.wrap(bytes));
                        if (image.lsn() > 0) {
                            pageLsns.put(pageKey, image.lsn());
                            pageLsnsDirty = true;
                        }
                    }
                    channel.force(true);
                }
                touched.add(tableId);
            } catch (IOException exception) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Failed to apply recovered pages for table " + tableId.value(), exception);
            }
        });
        persistPageLsnsIfDirty();
        discardUnflushedChanges(List.copyOf(touched));
    }

    public synchronized void flushDirtyPages() {
        try {
            Files.createDirectories(dataDir);
            for (TableState state : tables.values()) {
                flushTable(state);
            }
            persistPageLsnsIfDirty();
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to flush dirty pages", exception);
        }
    }

    public synchronized void markCommittedPageLsns(List<Storage.PageImage> pageImages) {
        for (Storage.PageImage pageImage : pageImages) {
            pageLsns.put(new PageKey(pageImage.tableId(), pageImage.pageNumber()), pageImage.lsn());
            pageLsnsDirty = true;
        }
    }

    int cachedPageCount() {
        return cache.size();
    }

    int maxCachedPages() {
        return maxCachedPages;
    }

    @Override
    public synchronized void close() {
        flushDirtyPages();
        cache.clear();
        tables.clear();
    }

    private void flushTable(TableState state) throws IOException {
        boolean hasDirtyPage = cache.values().stream().anyMatch(page -> page.key.tableId().equals(state.tableId) && page.dirty);
        if (!state.headerDirty && !hasDirtyPage) {
            return;
        }
        Files.createDirectories(dataDir);
        try (FileChannel channel = FileChannel.open(state.path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (state.headerDirty) {
                byte[] headerBytes = buildHeaderBytes(state);
                channel.position(0);
                channel.write(ByteBuffer.wrap(headerBytes));
                state.headerDirty = false;
            }
            for (CachedPage cachedPage : cache.values()) {
                if (!cachedPage.key.tableId().equals(state.tableId) || !cachedPage.dirty) {
                    continue;
                }
                byte[] pageBytes = checksummedPage(cachedPage.bytes);
                channel.position(FILE_HEADER_SIZE + (long) cachedPage.key.pageNumber() * PAGE_SIZE);
                channel.write(ByteBuffer.wrap(pageBytes));
                System.arraycopy(pageBytes, 0, cachedPage.bytes, 0, pageBytes.length);
                cachedPage.dirty = false;
            }
            channel.force(true);
        }
    }

    private List<Storage.PageImage> captureDirtyPageImages(LinkedHashSet<Common.ObjectId> changedTables) {
        List<Storage.PageImage> images = new ArrayList<>();
        for (Common.ObjectId tableId : changedTables) {
            TableState state = tables.get(tableId);
            if (state == null) {
                continue;
            }
            if (state.headerDirty) {
                images.add(new Storage.PageImage(tableId, Storage.FILE_HEADER_PAGE_NUMBER, 0L, buildHeaderBytes(state)));
            }
        }
        for (CachedPage cachedPage : cache.values()) {
            if (!cachedPage.dirty || !changedTables.contains(cachedPage.key.tableId())) {
                continue;
            }
            images.add(new Storage.PageImage(cachedPage.key.tableId(), cachedPage.key.pageNumber(), 0L, checksummedPage(cachedPage.bytes)));
        }
        return images;
    }

    private Storage.TableData materializeTable(TableState state) {
        LinkedHashMap<Common.RowId, List<Storage.RowVersion>> chains = new LinkedHashMap<>();
        for (int pageNumber = 0; pageNumber < state.pageCount; pageNumber++) {
            CachedPage cachedPage = pinExistingPage(state, pageNumber);
            try {
                ByteBuffer page = ByteBuffer.wrap(cachedPage.bytes).order(ByteOrder.BIG_ENDIAN);
                if (page.getInt(0) != DATA_PAGE_MAGIC) {
                    continue;
                }
                int slotCount = Short.toUnsignedInt(page.getShort(12));
                int freeEnd = Short.toUnsignedInt(page.getShort(16));
                for (int slot = 0; slot < slotCount; slot++) {
                    int slotOffset = DATA_PAGE_HEADER_SIZE + slot * SLOT_ENTRY_SIZE;
                    int recordOffset = Short.toUnsignedInt(page.getShort(slotOffset));
                    int recordLength = Short.toUnsignedInt(page.getShort(slotOffset + Short.BYTES));
                    if (recordLength == 0) {
                        continue;
                    }
                    if (recordOffset < freeEnd || recordOffset + recordLength > PAGE_SIZE) {
                        throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                                "Corrupt slot entry while materializing table " + state.tableId.value());
                    }
                    Storage.RowVersion version = decodeRecord(state, cachedPage.bytes, recordOffset, recordLength);
                    chains.computeIfAbsent(version.rowId(), ignored -> new ArrayList<>()).add(version);
                }
            } finally {
                unpin(cachedPage);
            }
        }
        LinkedHashMap<Common.RowId, List<Storage.RowVersion>> ordered = new LinkedHashMap<>();
        chains.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Common.RowId::value)))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingLong(Storage.RowVersion::createdAtCommitSequence)
                                .thenComparingLong(Storage.RowVersion::deletedAtCommitSequence))
                        .toList()));
        return new Storage.TableData(state.nextRowId, ordered);
    }

    private void appendVersion(TableState state, Storage.RowVersion version) {
        EncodedRecord encodedRecord = encodeRecord(state, version);
        int required = encodedRecord.bytes().length + SLOT_ENTRY_SIZE;
        Integer targetPageNumber = state.dataPageFreeBytes.entrySet().stream()
                .filter(entry -> entry.getValue() >= required)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (targetPageNumber == null) {
            targetPageNumber = createDataPage(state).key.pageNumber();
        }
        CachedPage cachedPage = pinExistingPage(state, targetPageNumber);
        try {
            ByteBuffer page = ByteBuffer.wrap(cachedPage.bytes).order(ByteOrder.BIG_ENDIAN);
            int slotCount = Short.toUnsignedInt(page.getShort(12));
            int freeStart = Short.toUnsignedInt(page.getShort(14));
            int freeEnd = Short.toUnsignedInt(page.getShort(16));
            if (freeEnd - freeStart < required) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Insufficient free space on target page " + targetPageNumber + " for table " + state.tableId.value());
            }
            int newRecordOffset = freeEnd - encodedRecord.bytes().length;
            System.arraycopy(encodedRecord.bytes(), 0, cachedPage.bytes, newRecordOffset, encodedRecord.bytes().length);
            int slotOffset = DATA_PAGE_HEADER_SIZE + slotCount * SLOT_ENTRY_SIZE;
            page.putShort(slotOffset, (short) newRecordOffset);
            page.putShort(slotOffset + Short.BYTES, (short) encodedRecord.bytes().length);
            page.putShort(12, (short) (slotCount + 1));
            page.putShort(14, (short) (freeStart + SLOT_ENTRY_SIZE));
            page.putShort(16, (short) newRecordOffset);
            cachedPage.dirty = true;
            state.rowVersionCount++;
            state.headerDirty = true;
            state.dataPageFreeBytes.put(targetPageNumber, newRecordOffset - (freeStart + SLOT_ENTRY_SIZE));
            state.latestRecordPointers.put(version.rowId(), new RecordPointer(targetPageNumber, newRecordOffset,
                    encodedRecord.bytes().length, version.createdAtCommitSequence(), version.deletedAtCommitSequence()));
        } finally {
            unpin(cachedPage);
        }
    }

    private void markVersionDeleted(TableState state, Common.RowId rowId, long deletedAtCommitSequence) {
        RecordPointer pointer = state.latestRecordPointers.get(rowId);
        if (pointer == null) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "No persisted row " + rowId.value() + " exists in table " + state.tableId.value());
        }
        if (pointer.deletedAtCommitSequence != 0) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Persisted row " + rowId.value() + " is already deleted in table " + state.tableId.value());
        }
        CachedPage cachedPage = pinExistingPage(state, pointer.pageNumber);
        try {
            ByteBuffer.wrap(cachedPage.bytes).order(ByteOrder.BIG_ENDIAN)
                    .putLong(pointer.recordOffset + Long.BYTES * 2, deletedAtCommitSequence);
            cachedPage.dirty = true;
            state.latestRecordPointers.put(rowId, pointer.withDeletedAt(deletedAtCommitSequence));
        } finally {
            unpin(cachedPage);
        }
    }

    private EncodedRecord encodeRecord(TableState state, Storage.RowVersion rowVersion) {
        List<ValueEncoding> encodings = new ArrayList<>(rowVersion.values().size());
        int size = Long.BYTES * 3 + Short.BYTES;
        List<TextCandidate> textCandidates = new ArrayList<>();
        for (int index = 0; index < rowVersion.values().size(); index++) {
            Common.Value value = rowVersion.values().get(index);
            Common.Value normalized = value == null ? Common.Value.nullValue(Common.DataType.TEXT) : value;
            ValueEncoding encoding = ValueEncoding.inline(normalized, inlinePayload(normalized));
            encodings.add(encoding);
            size += 2 + encoding.payload().length;
            if (!normalized.isNull() && normalized.type() == Common.DataType.TEXT) {
                textCandidates.add(new TextCandidate(index, normalized.asText().getBytes(StandardCharsets.UTF_8).length));
            }
        }

        LinkedHashSet<Integer> overflowIndexes = new LinkedHashSet<>();
        for (TextCandidate candidate : textCandidates) {
            if (candidate.length() > INLINE_TEXT_THRESHOLD) {
                overflowIndexes.add(candidate.index());
            }
        }
        size = estimateRecordSize(rowVersion.values(), textCandidates, overflowIndexes);
        if (size > MAX_RECORD_SIZE) {
            List<TextCandidate> sortedCandidates = textCandidates.stream()
                    .sorted(Comparator.comparingInt(TextCandidate::length).reversed())
                    .toList();
            for (TextCandidate candidate : sortedCandidates) {
                if (size <= MAX_RECORD_SIZE) {
                    break;
                }
                if (overflowIndexes.add(candidate.index())) {
                    size = estimateRecordSize(rowVersion.values(), textCandidates, overflowIndexes);
                }
            }
        }
        if (size > MAX_RECORD_SIZE) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Row version remains too large after overflow encoding for table " + state.tableId.value());
        }

        for (int index : overflowIndexes) {
            Common.Value value = rowVersion.values().get(index);
            byte[] bytes = value.asText().getBytes(StandardCharsets.UTF_8);
            OverflowPointer pointer = allocateOverflow(state, bytes);
            encodings.set(index, ValueEncoding.overflow(value, pointer));
        }

        int finalSize = Long.BYTES * 3 + Short.BYTES;
        for (ValueEncoding encoding : encodings) {
            finalSize += 2 + encoding.payload().length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(finalSize).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(rowVersion.rowId().value());
        buffer.putLong(rowVersion.createdAtCommitSequence());
        buffer.putLong(rowVersion.deletedAtCommitSequence());
        buffer.putShort((short) rowVersion.values().size());
        for (ValueEncoding encoding : encodings) {
            buffer.put((byte) encoding.value().type().ordinal());
            byte flags = 0;
            if (encoding.value().isNull()) {
                flags |= FLAG_NULL;
            }
            if (encoding.overflow()) {
                flags |= FLAG_OVERFLOW;
            }
            buffer.put(flags);
            buffer.put(encoding.payload());
        }
        return new EncodedRecord(buffer.array());
    }

    private int estimateRecordSize(List<Common.Value> values, List<TextCandidate> textCandidates, LinkedHashSet<Integer> overflowIndexes) {
        int total = Long.BYTES * 3 + Short.BYTES;
        for (int index = 0; index < values.size(); index++) {
            Common.Value value = values.get(index);
            Common.Value normalized = value == null ? Common.Value.nullValue(Common.DataType.TEXT) : value;
            total += 2;
            if (normalized.isNull()) {
                continue;
            }
            total += switch (normalized.type()) {
                case INTEGER -> Integer.BYTES;
                case BIGINT -> Long.BYTES;
                case BOOLEAN -> 1;
                case TEXT -> overflowIndexes.contains(index)
                        ? Integer.BYTES * 2
                        : Integer.BYTES + normalized.asText().getBytes(StandardCharsets.UTF_8).length;
                case DECIMAL, TIMESTAMP -> Integer.BYTES + normalized.asText().getBytes(StandardCharsets.UTF_8).length;
                case DATE, TIME -> Long.BYTES;
            };
        }
        return total;
    }

    private byte[] inlinePayload(Common.Value value) {
        if (value.isNull()) {
            return new byte[0];
        }
        return switch (value.type()) {
            case INTEGER -> ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value.asInt()).array();
            case BIGINT -> ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value.asLong()).array();
            case BOOLEAN -> new byte[]{(byte) (value.asBoolean() ? 1 : 0)};
            case TEXT -> {
                byte[] bytes = value.asText().getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bytes.length).order(ByteOrder.BIG_ENDIAN);
                buffer.putInt(bytes.length);
                buffer.put(bytes);
                yield buffer.array();
            }
            case DECIMAL, TIMESTAMP -> {
                byte[] bytes = value.asText().getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bytes.length).order(ByteOrder.BIG_ENDIAN);
                buffer.putInt(bytes.length);
                buffer.put(bytes);
                yield buffer.array();
            }
            case DATE -> ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value.asDate().toEpochDay()).array();
            case TIME -> ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value.asTime().toNanoOfDay()).array();
        };
    }

    private OverflowPointer allocateOverflow(TableState state, byte[] bytes) {
        int firstPageNumber = NO_NEXT_OVERFLOW_PAGE;
        int previousPageNumber = NO_NEXT_OVERFLOW_PAGE;
        int offset = 0;
        while (offset < bytes.length) {
            CachedPage page = createOverflowPage(state);
            int pageNumber = page.key.pageNumber();
            if (firstPageNumber == NO_NEXT_OVERFLOW_PAGE) {
                firstPageNumber = pageNumber;
            }
            int chunkLength = Math.min(PAGE_SIZE - OVERFLOW_PAGE_HEADER_SIZE, bytes.length - offset);
            ByteBuffer buffer = ByteBuffer.wrap(page.bytes).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(0, OVERFLOW_PAGE_MAGIC);
            buffer.putInt(4, 0);
            buffer.putInt(8, NO_NEXT_OVERFLOW_PAGE);
            buffer.putInt(12, chunkLength);
            System.arraycopy(bytes, offset, page.bytes, OVERFLOW_PAGE_HEADER_SIZE, chunkLength);
            Arrays.fill(page.bytes, OVERFLOW_PAGE_HEADER_SIZE + chunkLength, PAGE_SIZE, (byte) 0);
            page.dirty = true;
            unpin(page);
            if (previousPageNumber != NO_NEXT_OVERFLOW_PAGE) {
                CachedPage previous = pinExistingPage(state, previousPageNumber);
                try {
                    ByteBuffer.wrap(previous.bytes).order(ByteOrder.BIG_ENDIAN).putInt(8, pageNumber);
                    previous.dirty = true;
                } finally {
                    unpin(previous);
                }
            }
            previousPageNumber = pageNumber;
            offset += chunkLength;
        }
        return new OverflowPointer(firstPageNumber, bytes.length);
    }

    private Storage.RowVersion decodeRecord(TableState state, byte[] sourcePage, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(sourcePage, offset, length).slice().order(ByteOrder.BIG_ENDIAN);
        Common.RowId rowId = new Common.RowId(buffer.getLong());
        long createdAt = buffer.getLong();
        long deletedAt = buffer.getLong();
        int valueCount = Short.toUnsignedInt(buffer.getShort());
        List<Common.Value> values = new ArrayList<>(valueCount);
        for (int index = 0; index < valueCount; index++) {
            Common.DataType type = Common.DataType.values()[Byte.toUnsignedInt(buffer.get())];
            byte flags = buffer.get();
            boolean isNull = (flags & FLAG_NULL) != 0;
            boolean overflow = (flags & FLAG_OVERFLOW) != 0;
            if (isNull) {
                values.add(Common.Value.nullValue(type));
                continue;
            }
            if (overflow) {
                int totalLength = buffer.getInt();
                int firstPageNumber = buffer.getInt();
                byte[] bytes = readOverflow(state, firstPageNumber, totalLength);
                values.add(Common.Value.text(new String(bytes, StandardCharsets.UTF_8)));
                continue;
            }
            values.add(switch (type) {
                case INTEGER -> Common.Value.integer(buffer.getInt());
                case BIGINT -> Common.Value.bigint(buffer.getLong());
                case BOOLEAN -> Common.Value.bool(buffer.get() != 0);
                case TEXT -> {
                    int textLength = buffer.getInt();
                    byte[] encoded = new byte[textLength];
                    buffer.get(encoded);
                    yield Common.Value.text(new String(encoded, StandardCharsets.UTF_8));
                }
                case DECIMAL -> {
                    int decimalLength = buffer.getInt();
                    byte[] encoded = new byte[decimalLength];
                    buffer.get(encoded);
                    yield Common.Value.decimal(new java.math.BigDecimal(new String(encoded, StandardCharsets.UTF_8)));
                }
                case DATE -> Common.Value.date(java.time.LocalDate.ofEpochDay(buffer.getLong()));
                case TIME -> Common.Value.time(java.time.LocalTime.ofNanoOfDay(buffer.getLong()));
                case TIMESTAMP -> {
                    int timestampLength = buffer.getInt();
                    byte[] encoded = new byte[timestampLength];
                    buffer.get(encoded);
                    yield Common.Value.timestamp(Common.Value.parseTimestamp(new String(encoded, StandardCharsets.UTF_8)));
                }
            });
        }
        return new Storage.RowVersion(rowId, createdAt, deletedAt, values);
    }

    private byte[] readOverflow(TableState state, int firstPageNumber, int totalLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(totalLength);
        int pageNumber = firstPageNumber;
        while (pageNumber != NO_NEXT_OVERFLOW_PAGE && out.size() < totalLength) {
            CachedPage page = pinExistingPage(state, pageNumber);
            try {
                ByteBuffer buffer = ByteBuffer.wrap(page.bytes).order(ByteOrder.BIG_ENDIAN);
                if (buffer.getInt(0) != OVERFLOW_PAGE_MAGIC) {
                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Expected overflow page " + pageNumber + " in table " + state.tableId.value());
                }
                int nextPage = buffer.getInt(8);
                int payloadLength = buffer.getInt(12);
                out.write(page.bytes, OVERFLOW_PAGE_HEADER_SIZE, payloadLength);
                pageNumber = nextPage;
            } finally {
                unpin(page);
            }
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length != totalLength) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Overflow chain length mismatch in table " + state.tableId.value());
        }
        return bytes;
    }

    private RecordMetadata readRecordMetadata(byte[] sourcePage, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(sourcePage, offset, Math.min(length, Long.BYTES * 3)).slice().order(ByteOrder.BIG_ENDIAN);
        Common.RowId rowId = new Common.RowId(buffer.getLong());
        long createdAt = buffer.getLong();
        long deletedAt = buffer.getLong();
        return new RecordMetadata(rowId, createdAt, deletedAt);
    }

    private CachedPage createDataPage(TableState state) {
        CachedPage page = createPage(state, DATA_PAGE_MAGIC);
        ByteBuffer buffer = ByteBuffer.wrap(page.bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0, DATA_PAGE_MAGIC);
        buffer.putInt(4, 0);
        buffer.putInt(8, page.key.pageNumber());
        buffer.putShort(12, (short) 0);
        buffer.putShort(14, (short) DATA_PAGE_HEADER_SIZE);
        buffer.putShort(16, (short) PAGE_SIZE);
        buffer.putShort(18, (short) 0);
        page.dirty = true;
        state.dataPageFreeBytes.put(page.key.pageNumber(), PAGE_SIZE - DATA_PAGE_HEADER_SIZE);
        unpin(page);
        return pinExistingPage(state, page.key.pageNumber());
    }

    private CachedPage createOverflowPage(TableState state) {
        CachedPage page = createPage(state, OVERFLOW_PAGE_MAGIC);
        ByteBuffer buffer = ByteBuffer.wrap(page.bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0, OVERFLOW_PAGE_MAGIC);
        buffer.putInt(4, 0);
        buffer.putInt(8, NO_NEXT_OVERFLOW_PAGE);
        buffer.putInt(12, 0);
        page.dirty = true;
        return page;
    }

    private CachedPage createPage(TableState state, int pageMagic) {
        int pageNumber = state.pageCount;
        state.pageCount++;
        state.headerDirty = true;
        CachedPage page = new CachedPage(new PageKey(state.tableId, pageNumber), new byte[PAGE_SIZE]);
        page.pins = 1;
        ByteBuffer.wrap(page.bytes).order(ByteOrder.BIG_ENDIAN).putInt(0, pageMagic);
        putIntoCache(page);
        return page;
    }

    private CachedPage pinExistingPage(TableState state, int pageNumber) {
        PageKey key = new PageKey(state.tableId, pageNumber);
        CachedPage cachedPage = cache.get(key);
        if (cachedPage != null) {
            cachedPage.pins++;
            return cachedPage;
        }
        evictIfNeeded();
        byte[] bytes = readPage(state, pageNumber);
        CachedPage loaded = new CachedPage(key, bytes);
        loaded.pins = 1;
        putIntoCache(loaded);
        return loaded;
    }

    private void putIntoCache(CachedPage page) {
        cache.put(page.key, page);
        evictIfNeeded();
    }

    private void unpin(CachedPage page) {
        if (page.pins == 0) {
            throw new IllegalStateException("Page already unpinned: " + page.key);
        }
        page.pins--;
    }

    private void evictIfNeeded() {
        while (cache.size() > maxCachedPages) {
            Map.Entry<PageKey, CachedPage> evicted = cache.entrySet().stream()
                    .filter(entry -> entry.getValue().pins == 0)
                    .findFirst()
                    .orElseThrow(() -> new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Buffer cache exhausted with all pages pinned"));
            if (evicted.getValue().dirty) {
                try {
                    flushTable(tables.get(evicted.getKey().tableId()));
                } catch (IOException exception) {
                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Failed to flush dirty page during eviction", exception);
                }
            }
            cache.remove(evicted.getKey());
        }
    }

    private byte[] readPage(TableState state, int pageNumber) {
        if (pageNumber < 0 || pageNumber >= state.pageCount) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Page " + pageNumber + " is outside table " + state.tableId.value());
        }
        try (FileChannel channel = FileChannel.open(state.path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE);
            channel.position(FILE_HEADER_SIZE + (long) pageNumber * PAGE_SIZE);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Unexpected end of table file " + state.path.getFileName());
                }
            }
            byte[] bytes = buffer.array();
            verifyChecksum(bytes, PAGE_CHECKSUM_OFFSET, "table page " + pageNumber);
            return bytes;
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to read table page " + pageNumber + " for table " + state.tableId.value(), exception);
        }
    }

    private TableState loadOrCreateTableState(Common.ObjectId tableId) {
        TableState existing = tables.get(tableId);
        if (existing != null) {
            return existing;
        }
        Path path = tablePath(tableId);
        if (Files.exists(path) && isLiveTableFile(path)) {
            TableState loaded = loadTableState(tableId, path);
            tables.put(tableId, loaded);
            return loaded;
        }
        TableState created = newEmptyTableState(tableId);
        tables.put(tableId, created);
        return created;
    }

    private TableState loadOrRefreshTableState(Common.ObjectId tableId) {
        Path path = tablePath(tableId);
        TableState loaded = loadTableState(tableId, path);
        tables.put(tableId, loaded);
        return loaded;
    }

    private TableState loadTableState(Common.ObjectId tableId, Path path) {
        try {
            byte[] headerBytes = Files.readAllBytes(path);
            if (headerBytes.length < FILE_HEADER_SIZE) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Truncated live table file: " + path.getFileName());
            }
            verifyChecksum(Arrays.copyOf(headerBytes, FILE_HEADER_SIZE), FILE_CHECKSUM_OFFSET, "table header");
            ByteBuffer header = ByteBuffer.wrap(headerBytes, 0, FILE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            if (header.getInt() != FILE_MAGIC) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Unexpected table file magic in " + path.getFileName());
            }
            int version = header.getInt();
            if (version != LIVE_FILE_VERSION) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Expected live table format version 3 but found " + version + " in " + path.getFileName());
            }
            int pageSize = header.getInt();
            if (pageSize != PAGE_SIZE) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Unexpected page size " + pageSize + " in " + path.getFileName());
            }
            int headerSize = header.getInt();
            if (headerSize != FILE_HEADER_SIZE) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Unexpected header size " + headerSize + " in " + path.getFileName());
            }
            Common.ObjectId storedTableId = new Common.ObjectId(header.getLong());
            if (!storedTableId.equals(tableId)) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Table file " + path.getFileName() + " belongs to table " + storedTableId.value());
            }
            long nextRowId = header.getLong();
            int pageCount = header.getInt();
            int rowVersionCount = header.getInt();
            TableState state = new TableState(tableId, path, nextRowId, pageCount, rowVersionCount);
            for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
                CachedPage cachedPage = pinExistingPage(state, pageNumber);
                try {
                    ByteBuffer page = ByteBuffer.wrap(cachedPage.bytes).order(ByteOrder.BIG_ENDIAN);
                    int magic = page.getInt(0);
                    if (magic == DATA_PAGE_MAGIC) {
                        int storedPageNumber = page.getInt(8);
                        if (storedPageNumber != pageNumber) {
                            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                                    "Out-of-order page number " + storedPageNumber + " in table " + tableId.value());
                        }
                        int slotCount = Short.toUnsignedInt(page.getShort(12));
                        int freeStart = Short.toUnsignedInt(page.getShort(14));
                        int freeEnd = Short.toUnsignedInt(page.getShort(16));
                        if (freeStart < DATA_PAGE_HEADER_SIZE || freeEnd < DATA_PAGE_HEADER_SIZE || freeStart > freeEnd || freeEnd > PAGE_SIZE) {
                            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                                    "Corrupt free space pointers in table " + tableId.value() + " page " + pageNumber);
                        }
                        state.dataPageFreeBytes.put(pageNumber, freeEnd - freeStart);
                        for (int slot = 0; slot < slotCount; slot++) {
                            int slotOffset = DATA_PAGE_HEADER_SIZE + slot * SLOT_ENTRY_SIZE;
                            int recordOffset = Short.toUnsignedInt(page.getShort(slotOffset));
                            int recordLength = Short.toUnsignedInt(page.getShort(slotOffset + Short.BYTES));
                            if (recordLength == 0) {
                                continue;
                            }
                            RecordMetadata metadata = readRecordMetadata(cachedPage.bytes, recordOffset, recordLength);
                            RecordPointer pointer = new RecordPointer(pageNumber, recordOffset, recordLength,
                                    metadata.createdAtCommitSequence(), metadata.deletedAtCommitSequence());
                            state.latestRecordPointers.merge(metadata.rowId(), pointer, (left, right) -> left.newer(right) ? left : right);
                        }
                    } else if (magic != OVERFLOW_PAGE_MAGIC) {
                        throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                                "Unknown live page type in table " + tableId.value() + " page " + pageNumber);
                    }
                } finally {
                    unpin(cachedPage);
                }
            }
            state.headerDirty = false;
            return state;
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to load live table state for table " + tableId.value(), exception);
        }
    }

    private TableState newEmptyTableState(Common.ObjectId tableId) {
        return new TableState(tableId, tablePath(tableId), 1, 0, 0);
    }

    private void clearLiveTableFiles() throws IOException {
        try (var stream = Files.list(dataDir)) {
            for (Path path : stream.filter(file -> file.getFileName().toString().endsWith(".tbl")).toList()) {
                Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(pageLsnPath);
    }

    private void loadPersistedPageLsns() throws IOException {
        pageLsns.clear();
        if (!Files.exists(pageLsnPath)) {
            pageLsnsDirty = false;
            return;
        }
        for (String line : Files.readAllLines(pageLsnPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|");
            pageLsns.put(new PageKey(new Common.ObjectId(Long.parseLong(parts[0])), Integer.parseInt(parts[1])),
                    Long.parseLong(parts[2]));
        }
        pageLsnsDirty = false;
    }

    private void reloadPersistedPageLsns() {
        try {
            loadPersistedPageLsns();
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to reload page LSN metadata", exception);
        }
    }

    private void persistPageLsnsIfDirty() {
        if (!pageLsnsDirty) {
            return;
        }
        try {
            List<String> lines = pageLsns.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<PageKey, Long> entry) -> entry.getKey().tableId().value())
                            .thenComparingInt(entry -> entry.getKey().pageNumber()))
                    .map(entry -> entry.getKey().tableId().value() + "|" + entry.getKey().pageNumber() + "|" + entry.getValue())
                    .toList();
            Files.write(pageLsnPath, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            pageLsnsDirty = false;
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to persist page LSN metadata", exception);
        }
    }

    private Path tablePath(Common.ObjectId tableId) {
        return dataDir.resolve("table-" + tableId.value() + ".tbl");
    }

    private Common.ObjectId tableIdFromPath(Path path) {
        String name = path.getFileName().toString();
        return new Common.ObjectId(Long.parseLong(name.substring("table-".length(), name.length() - ".tbl".length())));
    }

    private boolean isLiveTableFile(Path path) {
        try {
            if (!Files.exists(path) || Files.size(path) < FILE_HEADER_SIZE) {
                return false;
            }
            byte[] headerBytes = Files.readAllBytes(path);
            if (headerBytes.length < FILE_HEADER_SIZE) {
                return false;
            }
            ByteBuffer header = ByteBuffer.wrap(headerBytes, 0, FILE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            return header.getInt() == FILE_MAGIC && header.getInt() == LIVE_FILE_VERSION;
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to inspect table file " + path.getFileName(), exception);
        }
    }

    private byte[] buildHeaderBytes(TableState state) {
        ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        header.putInt(FILE_MAGIC);
        header.putInt(LIVE_FILE_VERSION);
        header.putInt(PAGE_SIZE);
        header.putInt(FILE_HEADER_SIZE);
        header.putLong(state.tableId.value());
        header.putLong(state.nextRowId);
        header.putInt(state.pageCount);
        header.putInt(state.rowVersionCount);
        header.putInt(0);
        header.putLong(0L);
        header.putLong(0L);
        byte[] bytes = header.array();
        writeChecksum(bytes, FILE_CHECKSUM_OFFSET);
        return bytes;
    }

    private byte[] checksummedPage(byte[] pageBytes) {
        byte[] copy = pageBytes.clone();
        writeChecksum(copy, PAGE_CHECKSUM_OFFSET);
        return copy;
    }

    private void writeChecksum(byte[] bytes, int checksumOffset) {
        CRC32 crc32 = new CRC32();
        byte[] copy = bytes.clone();
        for (int index = checksumOffset; index < checksumOffset + Integer.BYTES; index++) {
            copy[index] = 0;
        }
        crc32.update(copy);
        ByteBuffer.wrap(bytes, checksumOffset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt((int) crc32.getValue());
    }

    private void verifyChecksum(byte[] bytes, int checksumOffset, String segmentName) {
        byte[] copy = bytes.clone();
        int stored = ByteBuffer.wrap(copy, checksumOffset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
        writeChecksum(copy, checksumOffset);
        int computed = ByteBuffer.wrap(copy, checksumOffset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
        if (stored != computed) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Checksum mismatch while reading " + segmentName);
        }
    }

    public record CommitResult(List<Storage.PageImage> pageImages, List<Common.ObjectId> changedTables) {
        public CommitResult {
            pageImages = List.copyOf(pageImages);
            changedTables = List.copyOf(changedTables);
        }
    }

    private record EncodedRecord(byte[] bytes) {
    }

    private record ValueEncoding(Common.Value value, boolean overflow, byte[] payload) {
        private static ValueEncoding inline(Common.Value value, byte[] payload) {
            return new ValueEncoding(value, false, payload);
        }

        private static ValueEncoding overflow(Common.Value value, OverflowPointer pointer) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(pointer.totalLength());
            buffer.putInt(pointer.firstPageNumber());
            return new ValueEncoding(value, true, buffer.array());
        }
    }

    private record OverflowPointer(int firstPageNumber, int totalLength) {
    }

    private record TextCandidate(int index, int length) {
    }

    private record RecordMetadata(Common.RowId rowId, long createdAtCommitSequence, long deletedAtCommitSequence) {
    }

    private record PageKey(Common.ObjectId tableId, int pageNumber) {
    }

    private static final class CachedPage {
        private final PageKey key;
        private final byte[] bytes;
        private int pins;
        private boolean dirty;

        private CachedPage(PageKey key, byte[] bytes) {
            this.key = key;
            this.bytes = bytes;
        }
    }

    private static final class TableState {
        private final Common.ObjectId tableId;
        private final Path path;
        private long nextRowId;
        private int pageCount;
        private int rowVersionCount;
        private boolean headerDirty;
        private final LinkedHashMap<Integer, Integer> dataPageFreeBytes = new LinkedHashMap<>();
        private final LinkedHashMap<Common.RowId, RecordPointer> latestRecordPointers = new LinkedHashMap<>();

        private TableState(Common.ObjectId tableId, Path path, long nextRowId, int pageCount, int rowVersionCount) {
            this.tableId = tableId;
            this.path = path;
            this.nextRowId = nextRowId;
            this.pageCount = pageCount;
            this.rowVersionCount = rowVersionCount;
            this.headerDirty = pageCount == 0;
        }
    }

    private record RecordPointer(int pageNumber, int recordOffset, int recordLength,
                                 long createdAtCommitSequence, long deletedAtCommitSequence) {
        private RecordPointer withDeletedAt(long deletedAt) {
            return new RecordPointer(pageNumber, recordOffset, recordLength, createdAtCommitSequence, deletedAt);
        }

        private boolean newer(RecordPointer other) {
            if (createdAtCommitSequence != other.createdAtCommitSequence) {
                return createdAtCommitSequence > other.createdAtCommitSequence;
            }
            return deletedAtCommitSequence >= other.deletedAtCommitSequence;
        }
    }
}
