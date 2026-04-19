package dev.javadb.engine;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SequenceStateStore {
    private static final String HEADER = "SEQUENCE_STATE|1";

    private final Path snapshotPath;
    private final Map<String, Long> nextValues = new LinkedHashMap<>();

    SequenceStateStore(Path home) {
        this.snapshotPath = home.resolve("catalog").resolve("sequence-state.snapshot");
        load();
    }

    synchronized void ensureDefinitions(Catalog.CatalogSnapshot snapshot) {
        boolean changed = false;
        for (Catalog.SequenceDefinition sequence : snapshot.sequencesById().values()) {
            changed |= ensureKey(sequenceKey(sequence.id()), normalizeOptions(sequence.options())) != null;
        }
        for (Catalog.TableDefinition table : snapshot.tablesById().values()) {
            for (Catalog.ColumnDefinition column : table.columns()) {
                if (column.identityDefinition() == null) {
                    continue;
                }
                changed |= ensureKey(identityKey(table.id(), column.ordinal()),
                        normalizeOptions(column.identityDefinition().options())) != null;
            }
        }
        if (changed) {
            persist();
        }
    }

    synchronized long nextValue(Catalog.SequenceDefinition sequence) {
        Catalog.SequenceOptions options = normalizeOptions(sequence.options());
        String key = sequenceKey(sequence.id());
        long current = nextValues.computeIfAbsent(key, ignored -> options.startWith());
        nextValues.put(key, advance(current, options));
        persist();
        return current;
    }

    synchronized long nextIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column) {
        Catalog.SequenceOptions options = normalizeOptions(column.identityDefinition().options());
        String key = identityKey(table.id(), column.ordinal());
        long current = nextValues.computeIfAbsent(key, ignored -> options.startWith());
        nextValues.put(key, advance(current, options));
        persist();
        return current;
    }

    synchronized void observeIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column, Common.Value value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.type() != Common.DataType.INTEGER && value.type() != Common.DataType.BIGINT) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                    "Identity values must be numeric for column " + column.name());
        }
        Catalog.SequenceOptions options = normalizeOptions(column.identityDefinition().options());
        String key = identityKey(table.id(), column.ordinal());
        long explicitValue = value.type() == Common.DataType.INTEGER ? value.asInt() : value.asLong();
        long current = nextValues.computeIfAbsent(key, ignored -> options.startWith());
        long next = current;
        long increment = options.incrementBy();
        if ((increment > 0 && explicitValue >= current) || (increment < 0 && explicitValue <= current)) {
            next = advance(explicitValue, options);
        }
        if (next != current) {
            nextValues.put(key, next);
            persist();
        }
    }

    private Long ensureKey(String key, Catalog.SequenceOptions options) {
        if (nextValues.containsKey(key)) {
            return null;
        }
        nextValues.put(key, options.startWith());
        return options.startWith();
    }

    private Catalog.SequenceOptions normalizeOptions(Catalog.SequenceOptions options) {
        if (options == null) {
            return new Catalog.SequenceOptions(1L, 1L, null, null, 20, false);
        }
        return new Catalog.SequenceOptions(
                options.startWith() == null ? 1L : options.startWith(),
                options.incrementBy() == null ? 1L : options.incrementBy(),
                options.minValue(),
                options.maxValue(),
                options.cacheSize() == null ? 20 : options.cacheSize(),
                options.cycle());
    }

    private long advance(long current, Catalog.SequenceOptions options) {
        long increment = options.incrementBy();
        if (increment == 0) {
            throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Sequence increment must not be zero");
        }
        long lowerBound = options.minValue() != null ? options.minValue() : (increment > 0 ? 1L : Long.MIN_VALUE);
        long upperBound = options.maxValue() != null ? options.maxValue() : (increment > 0 ? Long.MAX_VALUE : -1L);
        long candidate;
        try {
            candidate = Math.addExact(current, increment);
        } catch (ArithmeticException arithmeticException) {
            candidate = increment > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        if (candidate >= lowerBound && candidate <= upperBound) {
            return candidate;
        }
        if (options.cycle()) {
            return increment > 0 ? lowerBound : upperBound;
        }
        throw new Common.DatabaseException(Common.ErrorCode.CONSTRAINT_VIOLATION,
                "Sequence exhausted at value " + current);
    }

    private void load() {
        if (!Files.exists(snapshotPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(snapshotPath, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return;
            }
            if (!HEADER.equals(lines.getFirst())) {
                throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                        "Unsupported sequence state format: " + lines.getFirst());
            }
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", 2);
                if (parts.length != 2) {
                    throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                            "Corrupt sequence state line: " + line);
                }
                nextValues.put(parts[0], Long.parseLong(parts[1]));
            }
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to read sequence state", exception);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(snapshotPath.getParent());
            Path tempPath = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            for (Map.Entry<String, Long> entry : nextValues.entrySet()) {
                lines.add(entry.getKey() + "|" + entry.getValue());
            }
            Files.write(tempPath, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to persist sequence state", exception);
        }
    }

    private String sequenceKey(Common.ObjectId sequenceId) {
        return "SEQ:" + sequenceId.value();
    }

    private String identityKey(Common.ObjectId tableId, int ordinal) {
        return "IDENTITY:" + tableId.value() + ":" + ordinal;
    }
}
