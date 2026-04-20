package dev.daisybase.index;

import dev.daisybase.catalog.Catalog;
import dev.daisybase.common.Common;
import dev.daisybase.storage.Storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Indexes {
    private Indexes() {
    }

    public record IndexData(boolean unique, Map<String, List<Common.RowId>> entries) {
        public IndexData {
            entries = Map.copyOf(entries);
        }
    }

    public record IndexSnapshot(Map<Common.ObjectId, IndexData> indexesById) {
        public IndexSnapshot {
            indexesById = Map.copyOf(indexesById);
        }
    }

    public static IndexSnapshot rebuild(Catalog.CatalogSnapshot catalogSnapshot, Storage.StorageSnapshot storageSnapshot,
                                        long snapshotSequence) {
        LinkedHashMap<Common.ObjectId, IndexData> indexes = new LinkedHashMap<>();
        for (Catalog.IndexDefinition index : catalogSnapshot.indexesById().values()) {
            Catalog.TableDefinition table = catalogSnapshot.tablesById().get(index.tableId());
            LinkedHashMap<String, LinkedHashSet<Common.RowId>> entrySets = new LinkedHashMap<>();
            for (Storage.VisibleRow row : Storage.visibleRows(storageSnapshot, table.id(), snapshotSequence, null)) {
                String key = keyFor(index.columns(), table, row.values());
                entrySets.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(row.rowId());
            }
            LinkedHashMap<String, List<Common.RowId>> flattened = new LinkedHashMap<>();
            entrySets.forEach((key, rowIds) -> flattened.put(key, new ArrayList<>(rowIds)));
            indexes.put(index.id(), new IndexData(index.unique(), flattened));
        }
        return new IndexSnapshot(indexes);
    }

    public static Optional<Catalog.IndexDefinition> findSingleColumnIndex(Catalog.CatalogSnapshot snapshot,
                                                                          Catalog.TableDefinition table,
                                                                          String columnName) {
        return table.indexIds().stream()
                .map(snapshot.indexesById()::get)
                .filter(index -> index.columns().size() == 1 && index.columns().getFirst().equalsIgnoreCase(columnName))
                .findFirst();
    }

    public static List<Common.RowId> lookup(IndexSnapshot snapshot, Catalog.IndexDefinition indexDefinition, List<Common.Value> keyValues) {
        IndexData data = snapshot.indexesById().get(indexDefinition.id());
        if (data == null) {
            return List.of();
        }
        return data.entries().getOrDefault(keyValues.stream().map(Common.Values::encodeValue).reduce((a, b) -> a + "#" + b).orElse(""), List.of());
    }

    public static String keyFor(List<String> columns, Catalog.TableDefinition table, List<Common.Value> rowValues) {
        List<String> encoded = new ArrayList<>();
        for (String column : columns) {
            int ordinal = table.columnOrdinals().get(column.toLowerCase());
            encoded.add(Common.Values.encodeValue(rowValues.get(ordinal)));
        }
        return String.join("#", encoded);
    }
}