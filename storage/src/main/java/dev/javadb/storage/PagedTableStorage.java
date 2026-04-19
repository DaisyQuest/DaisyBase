package dev.javadb.storage;

import dev.javadb.common.Common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

final class PagedTableStorage {
    static final int PAGE_SIZE = 16 * 1024;

    private static final int FILE_MAGIC = 0x4A445442;
    private static final int PAGE_MAGIC = 0x50414745;
    private static final int FILE_VERSION = 2;
    private static final int FILE_HEADER_SIZE = 64;
    private static final int FILE_CHECKSUM_OFFSET = 40;
    private static final int PAGE_HEADER_SIZE = 20;
    private static final int PAGE_CHECKSUM_OFFSET = 4;
    private static final int SLOT_ENTRY_SIZE = 4;

    private PagedTableStorage() {
    }

    static void writeTable(Path path, Common.ObjectId tableId, Storage.TableData tableData) throws IOException {
        List<byte[]> records = encodeRecords(tableData.versionChains());
        List<byte[]> pages = buildPages(records);
        ByteBuffer fileHeader = ByteBuffer.allocate(FILE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        fileHeader.putInt(FILE_MAGIC);
        fileHeader.putInt(FILE_VERSION);
        fileHeader.putInt(PAGE_SIZE);
        fileHeader.putInt(FILE_HEADER_SIZE);
        fileHeader.putLong(tableId.value());
        fileHeader.putLong(tableData.nextRowId());
        fileHeader.putInt(pages.size());
        fileHeader.putInt(records.size());
        fileHeader.putInt(0);
        fileHeader.putLong(0L);
        fileHeader.putLong(0L);

        byte[] headerBytes = fileHeader.array();
        writeChecksum(headerBytes, FILE_CHECKSUM_OFFSET);

        ByteBuffer fileBuffer = ByteBuffer.allocate(FILE_HEADER_SIZE + pages.size() * PAGE_SIZE);
        fileBuffer.put(headerBytes);
        pages.forEach(fileBuffer::put);
        Files.write(path, fileBuffer.array());
    }

    static boolean isBinaryTableFile(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) < Integer.BYTES) {
            return false;
        }
        byte[] prefix = Files.readAllBytes(path);
        if (prefix.length < Integer.BYTES) {
            return false;
        }
        return ByteBuffer.wrap(prefix, 0, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt() == FILE_MAGIC;
    }

    static Storage.TableData readTable(Path path, Common.ObjectId expectedTableId) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < FILE_HEADER_SIZE) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Truncated table file: " + path.getFileName());
        }
        verifyChecksum(bytes, 0, FILE_HEADER_SIZE, FILE_CHECKSUM_OFFSET, "table header");
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, FILE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        int magic = header.getInt();
        if (magic != FILE_MAGIC) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Unknown table file magic for " + path.getFileName());
        }
        int version = header.getInt();
        if (version != FILE_VERSION) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Unsupported table file version " + version + " for " + path.getFileName());
        }
        int pageSize = header.getInt();
        if (pageSize != PAGE_SIZE) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Unsupported page size " + pageSize + " for " + path.getFileName());
        }
        int headerSize = header.getInt();
        if (headerSize != FILE_HEADER_SIZE) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Unsupported file header size " + headerSize + " for " + path.getFileName());
        }
        Common.ObjectId tableId = new Common.ObjectId(header.getLong());
        if (!tableId.equals(expectedTableId)) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Table file " + path.getFileName() + " contains table " + tableId.value()
                            + " but " + expectedTableId.value() + " was expected");
        }
        long nextRowId = header.getLong();
        int pageCount = header.getInt();
        int rowVersionCount = header.getInt();
        int expectedLength = FILE_HEADER_SIZE + pageCount * PAGE_SIZE;
        if (bytes.length != expectedLength) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Table file " + path.getFileName() + " has length " + bytes.length
                            + " but expected " + expectedLength);
        }

        LinkedHashMap<Common.RowId, List<Storage.RowVersion>> chains = new LinkedHashMap<>();
        int decodedVersions = 0;
        for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
            int pageOffset = FILE_HEADER_SIZE + pageNumber * PAGE_SIZE;
            verifyChecksum(bytes, pageOffset, PAGE_SIZE, PAGE_CHECKSUM_OFFSET, "page " + pageNumber);
            ByteBuffer page = ByteBuffer.wrap(bytes, pageOffset, PAGE_SIZE).slice().order(ByteOrder.BIG_ENDIAN);
            if (page.getInt() != PAGE_MAGIC) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Bad page magic in table file " + path.getFileName() + " page " + pageNumber);
            }
            page.position(8);
            int storedPageNumber = page.getInt();
            if (storedPageNumber != pageNumber) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Out-of-order page number " + storedPageNumber + " in table file " + path.getFileName());
            }
            int slotCount = Short.toUnsignedInt(page.getShort());
            int freeStart = Short.toUnsignedInt(page.getShort());
            int freeEnd = Short.toUnsignedInt(page.getShort());
            page.getShort();
            if (freeStart < PAGE_HEADER_SIZE || freeStart > PAGE_SIZE || freeEnd < PAGE_HEADER_SIZE || freeEnd > PAGE_SIZE || freeStart > freeEnd) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Corrupt free space pointers in table file " + path.getFileName() + " page " + pageNumber);
            }
            for (int slot = 0; slot < slotCount; slot++) {
                int slotOffset = PAGE_HEADER_SIZE + slot * SLOT_ENTRY_SIZE;
                int recordOffset = Short.toUnsignedInt(page.getShort(slotOffset));
                int recordLength = Short.toUnsignedInt(page.getShort(slotOffset + 2));
                if (recordLength == 0) {
                    continue;
                }
                if (recordOffset < freeEnd || recordOffset + recordLength > PAGE_SIZE) {
                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Corrupt slot entry in table file " + path.getFileName() + " page " + pageNumber);
                }
                Storage.RowVersion rowVersion = decodeRecord(ByteBuffer.wrap(bytes, pageOffset + recordOffset, recordLength).slice().order(ByteOrder.BIG_ENDIAN));
                chains.computeIfAbsent(rowVersion.rowId(), ignored -> new ArrayList<>()).add(rowVersion);
                decodedVersions++;
            }
        }
        if (decodedVersions != rowVersionCount) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Decoded " + decodedVersions + " row versions but header expected " + rowVersionCount
                            + " in " + path.getFileName());
        }
        LinkedHashMap<Common.RowId, List<Storage.RowVersion>> ordered = new LinkedHashMap<>();
        chains.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Common.RowId::value)))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingLong(Storage.RowVersion::createdAtCommitSequence)
                                .thenComparingLong(Storage.RowVersion::deletedAtCommitSequence))
                        .toList()));
        return new Storage.TableData(nextRowId, ordered);
    }

    private static List<byte[]> encodeRecords(Map<Common.RowId, List<Storage.RowVersion>> versionChains) {
        return versionChains.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Common.RowId::value)))
                .flatMap(entry -> entry.getValue().stream()
                        .sorted(Comparator.comparingLong(Storage.RowVersion::createdAtCommitSequence)
                                .thenComparingLong(Storage.RowVersion::deletedAtCommitSequence)))
                .map(PagedTableStorage::encodeRecord)
                .toList();
    }

    private static byte[] encodeRecord(Storage.RowVersion rowVersion) {
        int size = Long.BYTES * 3 + Short.BYTES;
        for (Common.Value value : rowVersion.values()) {
            size += 2;
            if (value != null && !value.isNull()) {
                size += switch (value.type()) {
                    case INTEGER -> Integer.BYTES;
                    case BIGINT -> Long.BYTES;
                    case BOOLEAN -> 1;
                    case TEXT, DECIMAL, TIMESTAMP, ARRAY, STRUCT, REF, SQLXML -> Integer.BYTES + variablePayload(value).length;
                    case BLOB, ROWID -> Integer.BYTES + variablePayload(value).length;
                    case DATE, TIME -> Long.BYTES;
                };
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(rowVersion.rowId().value());
        buffer.putLong(rowVersion.createdAtCommitSequence());
        buffer.putLong(rowVersion.deletedAtCommitSequence());
        buffer.putShort((short) rowVersion.values().size());
        for (Common.Value value : rowVersion.values()) {
            Common.Value normalized = value == null ? Common.Value.text(null) : value;
            buffer.put((byte) normalized.type().ordinal());
            buffer.put((byte) (normalized.isNull() ? 1 : 0));
            if (normalized.isNull()) {
                continue;
            }
            switch (normalized.type()) {
                case INTEGER -> buffer.putInt(normalized.asInt());
                case BIGINT -> buffer.putLong(normalized.asLong());
                case BOOLEAN -> buffer.put((byte) (normalized.asBoolean() ? 1 : 0));
                case TEXT, DECIMAL, TIMESTAMP, ARRAY, STRUCT, REF, SQLXML, BLOB, ROWID -> {
                    byte[] encoded = variablePayload(normalized);
                    buffer.putInt(encoded.length);
                    buffer.put(encoded);
                }
                case DATE -> buffer.putLong(normalized.asDate().toEpochDay());
                case TIME -> buffer.putLong(normalized.asTime().toNanoOfDay());
            }
        }
        return buffer.array();
    }

    private static Storage.RowVersion decodeRecord(ByteBuffer buffer) {
        Common.RowId rowId = new Common.RowId(buffer.getLong());
        long createdAt = buffer.getLong();
        long deletedAt = buffer.getLong();
        int valueCount = Short.toUnsignedInt(buffer.getShort());
        List<Common.Value> values = new ArrayList<>(valueCount);
        for (int index = 0; index < valueCount; index++) {
            Common.DataType type = Common.DataType.values()[Byte.toUnsignedInt(buffer.get())];
            boolean isNull = buffer.get() != 0;
            if (isNull) {
                values.add(Common.Value.nullValue(type));
                continue;
            }
            values.add(switch (type) {
                case INTEGER -> Common.Value.integer(buffer.getInt());
                case BIGINT -> Common.Value.bigint(buffer.getLong());
                case BOOLEAN -> Common.Value.bool(buffer.get() != 0);
                case TEXT, DECIMAL, TIMESTAMP, ARRAY, STRUCT, REF, SQLXML, BLOB, ROWID -> {
                    int length = buffer.getInt();
                    byte[] encoded = new byte[length];
                    buffer.get(encoded);
                    yield decodeVariableValue(type, encoded);
                }
                case DATE -> Common.Value.date(java.time.LocalDate.ofEpochDay(buffer.getLong()));
                case TIME -> Common.Value.time(java.time.LocalTime.ofNanoOfDay(buffer.getLong()));
            });
        }
        return new Storage.RowVersion(rowId, createdAt, deletedAt, values);
    }

    private static byte[] variablePayload(Common.Value value) {
        return switch (value.type()) {
            case TEXT, DECIMAL, TIMESTAMP, ARRAY, STRUCT, REF, SQLXML ->
                    value.asText().getBytes(StandardCharsets.UTF_8);
            case BLOB -> value.asBytes();
            case ROWID -> value.asRowIdBytes();
            case INTEGER, BIGINT, BOOLEAN, DATE, TIME ->
                    throw new IllegalStateException("Type " + value.type() + " is not length-encoded");
        };
    }

    private static Common.Value decodeVariableValue(Common.DataType type, byte[] encoded) {
        return switch (type) {
            case TEXT -> Common.Value.text(new String(encoded, StandardCharsets.UTF_8));
            case DECIMAL -> Common.Value.decimal(new java.math.BigDecimal(new String(encoded, StandardCharsets.UTF_8)));
            case TIMESTAMP -> Common.Value.timestamp(Common.Value.parseTimestamp(new String(encoded, StandardCharsets.UTF_8)));
            case ARRAY -> Common.Value.array((Common.ArrayValue) new Common.Value(Common.DataType.ARRAY,
                    new String(encoded, StandardCharsets.UTF_8)).raw());
            case STRUCT -> Common.Value.struct((Common.StructValue) new Common.Value(Common.DataType.STRUCT,
                    new String(encoded, StandardCharsets.UTF_8)).raw());
            case REF -> Common.Value.ref((Common.RefValue) new Common.Value(Common.DataType.REF,
                    new String(encoded, StandardCharsets.UTF_8)).raw());
            case SQLXML -> Common.Value.sqlxml(new String(encoded, StandardCharsets.UTF_8));
            case BLOB -> Common.Value.blob(encoded);
            case ROWID -> Common.Value.rowIdBytes(encoded);
            case INTEGER, BIGINT, BOOLEAN, DATE, TIME ->
                    throw new IllegalStateException("Type " + type + " is not length-encoded");
        };
    }

    private static List<byte[]> buildPages(List<byte[]> records) {
        List<byte[]> pages = new ArrayList<>();
        SlottedPageBuilder builder = new SlottedPageBuilder(0);
        for (byte[] record : records) {
            if (builder.tryAdd(record)) {
                continue;
            }
            pages.add(builder.finish());
            builder = new SlottedPageBuilder(pages.size());
            if (!builder.tryAdd(record)) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Row version is too large for a single storage page: " + record.length + " bytes");
            }
        }
        if (!builder.empty()) {
            pages.add(builder.finish());
        }
        return pages;
    }

    private static void writeChecksum(byte[] bytes, int checksumOffset) {
        CRC32 crc32 = new CRC32();
        byte[] copy = bytes.clone();
        for (int index = checksumOffset; index < checksumOffset + Integer.BYTES; index++) {
            copy[index] = 0;
        }
        crc32.update(copy);
        ByteBuffer.wrap(bytes, checksumOffset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt((int) crc32.getValue());
    }

    private static void verifyChecksum(byte[] bytes, int start, int length, int checksumOffsetWithinSegment, String segmentName) {
        byte[] segment = new byte[length];
        System.arraycopy(bytes, start, segment, 0, length);
        int stored = ByteBuffer.wrap(segment, checksumOffsetWithinSegment, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
        writeChecksum(segment, checksumOffsetWithinSegment);
        int computed = ByteBuffer.wrap(segment, checksumOffsetWithinSegment, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
        if (stored != computed) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Checksum mismatch while reading " + segmentName);
        }
    }

    private static final class SlottedPageBuilder {
        private final byte[] page = new byte[PAGE_SIZE];
        private final ByteBuffer header = ByteBuffer.wrap(page).order(ByteOrder.BIG_ENDIAN);
        private final int pageNumber;
        private int slotCount;
        private int freeStart = PAGE_HEADER_SIZE;
        private int freeEnd = PAGE_SIZE;

        private SlottedPageBuilder(int pageNumber) {
            this.pageNumber = pageNumber;
            header.putInt(PAGE_MAGIC);
            header.putInt(0);
            header.putInt(pageNumber);
            header.putShort((short) 0);
            header.putShort((short) freeStart);
            header.putShort((short) freeEnd);
            header.putShort((short) 0);
        }

        private boolean tryAdd(byte[] record) {
            int required = SLOT_ENTRY_SIZE + record.length;
            if (required > freeEnd - freeStart) {
                return false;
            }
            freeEnd -= record.length;
            System.arraycopy(record, 0, page, freeEnd, record.length);
            header.putShort(PAGE_HEADER_SIZE + slotCount * SLOT_ENTRY_SIZE, (short) freeEnd);
            header.putShort(PAGE_HEADER_SIZE + slotCount * SLOT_ENTRY_SIZE + Short.BYTES, (short) record.length);
            slotCount++;
            freeStart += SLOT_ENTRY_SIZE;
            header.putShort(12, (short) slotCount);
            header.putShort(14, (short) freeStart);
            header.putShort(16, (short) freeEnd);
            return true;
        }

        private boolean empty() {
            return slotCount == 0;
        }

        private byte[] finish() {
            writeChecksum(page, PAGE_CHECKSUM_OFFSET);
            return page.clone();
        }
    }
}
