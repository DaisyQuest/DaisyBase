package dev.javadb.catalog;

import dev.javadb.common.Common;
import dev.javadb.sql.SqlFrontend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class Catalog {
    private Catalog() {
    }

    public record QualifiedName(String schema, String name) {
        public QualifiedName {
            Objects.requireNonNull(name, "name");
        }

        public static QualifiedName from(SqlFrontend.QualifiedName name) {
            return new QualifiedName(name.schemaOrDefault(), name.name());
        }

        public String key() {
            return schema + "." + name;
        }

        public String toSql() {
            return schema + "." + name;
        }
    }

    public record SchemaDefinition(Common.ObjectId id, String name) {
    }

    public enum IdentityGeneration {
        ALWAYS,
        BY_DEFAULT
    }

    public record SequenceOptions(Long startWith, Long incrementBy, Long minValue, Long maxValue,
                                  Integer cacheSize, boolean cycle) {
    }

    public record IdentityDefinition(IdentityGeneration generation, SequenceOptions options) {
    }

    public record ColumnDefinition(int ordinal, String name, Common.DataType type, boolean nullable,
                                   boolean primaryKey, boolean unique, String checkExpressionSql,
                                   IdentityDefinition identityDefinition, Integer precision, Integer scale) {
        public ColumnDefinition(int ordinal, String name, Common.DataType type, boolean nullable,
                                boolean primaryKey, boolean unique, String checkExpressionSql,
                                IdentityDefinition identityDefinition) {
            this(ordinal, name, type, nullable, primaryKey, unique, checkExpressionSql, identityDefinition, null, null);
        }
    }

    public record TableDefinition(Common.ObjectId id, QualifiedName name, List<ColumnDefinition> columns,
                                  Map<String, Integer> columnOrdinals, List<Common.ObjectId> indexIds) {
        public TableDefinition {
            columns = List.copyOf(columns);
            columnOrdinals = Map.copyOf(columnOrdinals);
            indexIds = List.copyOf(indexIds);
        }

        public ColumnDefinition requireColumn(String column) {
            Integer ordinal = columnOrdinals.get(column.toLowerCase());
            if (ordinal == null) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR,
                        "Unknown column " + column + " on table " + name.toSql());
            }
            return columns.get(ordinal);
        }
    }

    public record IndexDefinition(Common.ObjectId id, String name, Common.ObjectId tableId, List<String> columns,
                                  boolean unique) {
        public IndexDefinition {
            columns = columns.stream().map(String::toLowerCase).toList();
        }
    }

    public record SequenceDefinition(Common.ObjectId id, QualifiedName name, SequenceOptions options) {
    }

    public enum RoutineKind {
        FUNCTION,
        PROCEDURE
    }

    public enum ParameterMode {
        IN,
        OUT,
        INOUT
    }

    public record RoutineParameter(int ordinal, String name, Common.DataType type, ParameterMode mode,
                                   Integer precision, Integer scale) {
        public RoutineParameter(int ordinal, String name, Common.DataType type, ParameterMode mode) {
            this(ordinal, name, type, mode, null, null);
        }
    }

    public record RoutineDefinition(Common.ObjectId id, QualifiedName name, RoutineKind kind, List<RoutineParameter> parameters,
                                    Common.DataType returnType, Integer returnPrecision, Integer returnScale, String bodySql) {
        public RoutineDefinition {
            parameters = List.copyOf(parameters);
        }

        public RoutineDefinition(Common.ObjectId id, QualifiedName name, RoutineKind kind, List<RoutineParameter> parameters,
                                 Common.DataType returnType, String bodySql) {
            this(id, name, kind, parameters, returnType, null, null, bodySql);
        }
    }

    public record CatalogSnapshot(long catalogVersion, Map<String, SchemaDefinition> schemasByName,
                                  Map<String, TableDefinition> tablesByName, Map<Common.ObjectId, TableDefinition> tablesById,
                                  Map<String, IndexDefinition> indexesByName, Map<Common.ObjectId, IndexDefinition> indexesById,
                                  Map<String, SequenceDefinition> sequencesByName, Map<Common.ObjectId, SequenceDefinition> sequencesById,
                                  Map<String, RoutineDefinition> routinesByName, Map<Common.ObjectId, RoutineDefinition> routinesById) {
        public CatalogSnapshot {
            schemasByName = Map.copyOf(schemasByName);
            tablesByName = Map.copyOf(tablesByName);
            tablesById = Map.copyOf(tablesById);
            indexesByName = Map.copyOf(indexesByName);
            indexesById = Map.copyOf(indexesById);
            sequencesByName = Map.copyOf(sequencesByName);
            sequencesById = Map.copyOf(sequencesById);
            routinesByName = Map.copyOf(routinesByName);
            routinesById = Map.copyOf(routinesById);
        }

        public TableDefinition requireTable(QualifiedName name) {
            TableDefinition table = tablesByName.get(name.key());
            if (table == null) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Unknown table " + name.toSql());
            }
            return table;
        }

        public Optional<TableDefinition> table(QualifiedName name) {
            return Optional.ofNullable(tablesByName.get(name.key()));
        }

        public Optional<SchemaDefinition> schema(String name) {
            return Optional.ofNullable(schemasByName.get(name.toLowerCase()));
        }

        public Optional<SequenceDefinition> sequence(QualifiedName name) {
            return Optional.ofNullable(sequencesByName.get(name.key()));
        }

        public Optional<RoutineDefinition> routine(QualifiedName name) {
            return Optional.ofNullable(routinesByName.get(name.key()));
        }
    }

    public sealed interface CatalogChange permits CreateSchemaChange, CreateTableChange, CreateIndexChange, CreateSequenceChange,
            CreateRoutineChange {
        String serialize();
    }

    public record CreateSchemaChange(Common.ObjectId schemaId, String schemaName) implements CatalogChange {
        @Override
        public String serialize() {
            return "CREATE_SCHEMA|" + schemaId.value() + "|" + schemaName;
        }
    }

    public record CreateTableChange(Common.ObjectId tableId, QualifiedName name, List<ColumnDefinition> columns) implements CatalogChange {
        public CreateTableChange {
            columns = List.copyOf(columns);
        }

        @Override
        public String serialize() {
            String columnsText = columns.stream()
                    .map(column -> String.join("~",
                            Integer.toString(column.ordinal()),
                            Common.Values.encodeString(column.name()),
                            column.type().name(),
                            Boolean.toString(column.nullable()),
                            Boolean.toString(column.primaryKey()),
                            Boolean.toString(column.unique()),
                            Common.Values.encodeString(column.checkExpressionSql() == null ? "" : column.checkExpressionSql()),
                            Common.Values.encodeString(serializeIdentity(column.identityDefinition())),
                            encodeNullableInteger(column.precision()),
                            encodeNullableInteger(column.scale())))
                    .collect(Collectors.joining(","));
            return "CREATE_TABLE|" + tableId.value() + "|" + Common.Values.encodeString(name.schema()) + "|"
                    + Common.Values.encodeString(name.name()) + "|" + columnsText;
        }
    }

    public record CreateIndexChange(Common.ObjectId indexId, String indexName, Common.ObjectId tableId, List<String> columns,
                                    boolean unique) implements CatalogChange {
        public CreateIndexChange {
            columns = List.copyOf(columns);
        }

        @Override
        public String serialize() {
            String columnsText = columns.stream().map(String::toLowerCase).collect(Collectors.joining(","));
            return "CREATE_INDEX|" + indexId.value() + "|" + Common.Values.encodeString(indexName) + "|"
                    + tableId.value() + "|" + unique + "|" + Common.Values.encodeString(columnsText);
        }
    }

    public record CreateSequenceChange(Common.ObjectId sequenceId, QualifiedName name, SequenceOptions options) implements CatalogChange {
        @Override
        public String serialize() {
            return "CREATE_SEQUENCE|" + sequenceId.value() + "|" + Common.Values.encodeString(name.schema()) + "|"
                    + Common.Values.encodeString(name.name()) + "|" + Common.Values.encodeString(serializeSequenceOptions(options));
        }
    }

    public record CreateRoutineChange(Common.ObjectId routineId, QualifiedName name, RoutineKind kind, List<RoutineParameter> parameters,
                                      Common.DataType returnType, Integer returnPrecision, Integer returnScale,
                                      String bodySql) implements CatalogChange {
        public CreateRoutineChange {
            parameters = List.copyOf(parameters);
        }

        public CreateRoutineChange(Common.ObjectId routineId, QualifiedName name, RoutineKind kind, List<RoutineParameter> parameters,
                                   Common.DataType returnType, String bodySql) {
            this(routineId, name, kind, parameters, returnType, null, null, bodySql);
        }

        @Override
        public String serialize() {
            return "CREATE_ROUTINE|" + routineId.value() + "|" + Common.Values.encodeString(name.schema()) + "|"
                    + Common.Values.encodeString(name.name()) + "|" + kind.name() + "|"
                    + Common.Values.encodeString(returnType == null ? "" : returnType.name()) + "|"
                    + encodeNullableInteger(returnPrecision) + "|"
                    + encodeNullableInteger(returnScale) + "|"
                    + Common.Values.encodeString(serializeParameters(parameters)) + "|"
                    + Common.Values.encodeString(bodySql == null ? "" : bodySql);
        }
    }

    public static CatalogChange deserializeChange(String text) {
        String[] parts = text.split("\\|", 10);
        return switch (parts[0]) {
            case "CREATE_SCHEMA" -> new CreateSchemaChange(new Common.ObjectId(Long.parseLong(parts[1])), parts[2]);
            case "CREATE_TABLE" -> {
                List<ColumnDefinition> columns = new ArrayList<>();
                if (parts.length > 4 && !parts[4].isBlank()) {
                    String[] columnTokens = parts[4].split(",");
                    for (String columnToken : columnTokens) {
                        String[] columnParts = columnToken.split("~", 10);
                        columns.add(new ColumnDefinition(
                                Integer.parseInt(columnParts[0]),
                                Common.Values.decodeString(columnParts[1]),
                                Common.DataType.valueOf(columnParts[2]),
                                Boolean.parseBoolean(columnParts[3]),
                                Boolean.parseBoolean(columnParts[4]),
                                Boolean.parseBoolean(columnParts[5]),
                                Common.Values.decodeString(columnParts[6]).isBlank() ? null : Common.Values.decodeString(columnParts[6]),
                                deserializeIdentity(Common.Values.decodeString(columnParts[7])),
                                decodeNullableInteger(columnParts, 8),
                                decodeNullableInteger(columnParts, 9)));
                    }
                }
                yield new CreateTableChange(new Common.ObjectId(Long.parseLong(parts[1])),
                        new QualifiedName(Common.Values.decodeString(parts[2]), Common.Values.decodeString(parts[3])),
                        columns);
            }
            case "CREATE_INDEX" -> new CreateIndexChange(new Common.ObjectId(Long.parseLong(parts[1])),
                    Common.Values.decodeString(parts[2]),
                    new Common.ObjectId(Long.parseLong(parts[3])),
                    List.of(Common.Values.decodeString(parts[5]).split(",")),
                    Boolean.parseBoolean(parts[4]));
            case "CREATE_SEQUENCE" -> new CreateSequenceChange(new Common.ObjectId(Long.parseLong(parts[1])),
                    new QualifiedName(Common.Values.decodeString(parts[2]), Common.Values.decodeString(parts[3])),
                    deserializeSequenceOptions(Common.Values.decodeString(parts[4])));
                case "CREATE_ROUTINE" -> new CreateRoutineChange(new Common.ObjectId(Long.parseLong(parts[1])),
                        new QualifiedName(Common.Values.decodeString(parts[2]), Common.Values.decodeString(parts[3])),
                        RoutineKind.valueOf(parts[4]),
                        deserializeParameters(Common.Values.decodeString(parts[8])),
                        Common.Values.decodeString(parts[5]).isBlank() ? null : Common.DataType.valueOf(Common.Values.decodeString(parts[5])),
                        decodeNullableInteger(parts, 6),
                        decodeNullableInteger(parts, 7),
                        Common.Values.decodeString(parts[9]));
            default -> throw new Common.DatabaseException(Common.ErrorCode.INTERNAL_ERROR, "Unknown catalog change: " + text);
        };
    }

    public static CatalogSnapshot bootstrap(Common.ObjectId publicSchemaId) {
        Map<String, SchemaDefinition> schemas = new LinkedHashMap<>();
        schemas.put("public", new SchemaDefinition(publicSchemaId, "public"));
        return new CatalogSnapshot(1, schemas, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static CatalogSnapshot applyChanges(CatalogSnapshot base, List<CatalogChange> changes) {
        Map<String, SchemaDefinition> schemas = new LinkedHashMap<>(base.schemasByName());
        Map<String, TableDefinition> tablesByName = new LinkedHashMap<>(base.tablesByName());
        Map<Common.ObjectId, TableDefinition> tablesById = new LinkedHashMap<>(base.tablesById());
        Map<String, IndexDefinition> indexesByName = new LinkedHashMap<>(base.indexesByName());
        Map<Common.ObjectId, IndexDefinition> indexesById = new LinkedHashMap<>(base.indexesById());
        Map<String, SequenceDefinition> sequencesByName = new LinkedHashMap<>(base.sequencesByName());
        Map<Common.ObjectId, SequenceDefinition> sequencesById = new LinkedHashMap<>(base.sequencesById());
        Map<String, RoutineDefinition> routinesByName = new LinkedHashMap<>(base.routinesByName());
        Map<Common.ObjectId, RoutineDefinition> routinesById = new LinkedHashMap<>(base.routinesById());

        for (CatalogChange change : changes) {
            switch (change) {
                case CreateSchemaChange schemaChange -> {
                    String schemaName = schemaChange.schemaName().toLowerCase();
                    if (schemas.containsKey(schemaName)) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Schema already exists: " + schemaName);
                    }
                    schemas.put(schemaName, new SchemaDefinition(schemaChange.schemaId(), schemaName));
                }
                case CreateTableChange tableChange -> {
                    QualifiedName tableName = new QualifiedName(tableChange.name().schema().toLowerCase(), tableChange.name().name().toLowerCase());
                    if (!schemas.containsKey(tableName.schema())) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Unknown schema: " + tableName.schema());
                    }
                    if (tablesByName.containsKey(tableName.key())) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Table already exists: " + tableName.toSql());
                    }
                    validateColumns(tableChange.columns());
                    Map<String, Integer> columnOrdinals = new LinkedHashMap<>();
                    for (ColumnDefinition column : tableChange.columns()) {
                        columnOrdinals.put(column.name().toLowerCase(), column.ordinal());
                    }
                    TableDefinition tableDefinition = new TableDefinition(tableChange.tableId(), tableName,
                            tableChange.columns(), columnOrdinals, List.of());
                    tablesByName.put(tableName.key(), tableDefinition);
                    tablesById.put(tableDefinition.id(), tableDefinition);
                }
                case CreateIndexChange indexChange -> {
                    String indexName = indexChange.indexName().toLowerCase();
                    if (indexesByName.containsKey(indexName)) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Index already exists: " + indexName);
                    }
                    TableDefinition tableDefinition = tablesById.get(indexChange.tableId());
                    if (tableDefinition == null) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Unknown table for index: " + indexName);
                    }
                    for (String column : indexChange.columns()) {
                        tableDefinition.requireColumn(column);
                    }
                    IndexDefinition indexDefinition = new IndexDefinition(indexChange.indexId(), indexName, indexChange.tableId(), indexChange.columns(), indexChange.unique());
                    indexesByName.put(indexName, indexDefinition);
                    indexesById.put(indexDefinition.id(), indexDefinition);
                    List<Common.ObjectId> newIndexIds = new ArrayList<>(tableDefinition.indexIds());
                    newIndexIds.add(indexDefinition.id());
                    TableDefinition updatedTable = new TableDefinition(tableDefinition.id(), tableDefinition.name(), tableDefinition.columns(), tableDefinition.columnOrdinals(), newIndexIds);
                    tablesByName.put(updatedTable.name().key(), updatedTable);
                    tablesById.put(updatedTable.id(), updatedTable);
                }
                case CreateSequenceChange sequenceChange -> {
                    QualifiedName sequenceName = new QualifiedName(sequenceChange.name().schema().toLowerCase(), sequenceChange.name().name().toLowerCase());
                    if (!schemas.containsKey(sequenceName.schema())) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Unknown schema: " + sequenceName.schema());
                    }
                    if (sequencesByName.containsKey(sequenceName.key())) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Sequence already exists: " + sequenceName.toSql());
                    }
                    SequenceDefinition definition = new SequenceDefinition(sequenceChange.sequenceId(), sequenceName, normalizeSequenceOptions(sequenceChange.options()));
                    sequencesByName.put(sequenceName.key(), definition);
                    sequencesById.put(definition.id(), definition);
                }
                case CreateRoutineChange routineChange -> {
                    QualifiedName routineName = new QualifiedName(routineChange.name().schema().toLowerCase(), routineChange.name().name().toLowerCase());
                    if (!schemas.containsKey(routineName.schema())) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Unknown schema: " + routineName.schema());
                    }
                    if (routinesByName.containsKey(routineName.key())) {
                        throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Routine already exists: " + routineName.toSql());
                    }
                    RoutineDefinition definition = new RoutineDefinition(routineChange.routineId(), routineName, routineChange.kind(),
                            routineChange.parameters(), routineChange.returnType(),
                            routineChange.returnPrecision(), routineChange.returnScale(), routineChange.bodySql());
                    routinesByName.put(routineName.key(), definition);
                    routinesById.put(definition.id(), definition);
                }
            }
        }

        return new CatalogSnapshot(base.catalogVersion() + changes.size(), schemas, tablesByName, tablesById, indexesByName, indexesById,
                sequencesByName, sequencesById, routinesByName, routinesById);
    }

    private static void validateColumns(List<ColumnDefinition> columns) {
        Set<String> names = new LinkedHashSet<>();
        for (ColumnDefinition column : columns) {
            String lowered = column.name().toLowerCase();
            if (!names.add(lowered)) {
                throw new Common.DatabaseException(Common.ErrorCode.SEMANTIC_ERROR, "Duplicate column: " + column.name());
            }
        }
    }

    private static SequenceOptions normalizeSequenceOptions(SequenceOptions options) {
        if (options == null) {
            return new SequenceOptions(1L, 1L, null, null, 20, false);
        }
        return new SequenceOptions(
                options.startWith() == null ? 1L : options.startWith(),
                options.incrementBy() == null ? 1L : options.incrementBy(),
                options.minValue(),
                options.maxValue(),
                options.cacheSize() == null ? 20 : options.cacheSize(),
                options.cycle());
    }

    private static String serializeIdentity(IdentityDefinition identityDefinition) {
        if (identityDefinition == null) {
            return "";
        }
        return identityDefinition.generation().name() + "~" + serializeSequenceOptions(identityDefinition.options());
    }

    private static IdentityDefinition deserializeIdentity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.split("~", 7);
        SequenceOptions options = deserializeSequenceOptions(String.join("~", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
        return new IdentityDefinition(IdentityGeneration.valueOf(parts[0]), options);
    }

    private static String serializeSequenceOptions(SequenceOptions options) {
        SequenceOptions normalized = normalizeSequenceOptions(options);
        return String.join("~",
                encodeNullableLong(normalized.startWith()),
                encodeNullableLong(normalized.incrementBy()),
                encodeNullableLong(normalized.minValue()),
                encodeNullableLong(normalized.maxValue()),
                encodeNullableInteger(normalized.cacheSize()),
                Boolean.toString(normalized.cycle()));
    }

    private static SequenceOptions deserializeSequenceOptions(String text) {
        if (text == null || text.isBlank()) {
            return normalizeSequenceOptions(null);
        }
        String[] parts = text.split("~", -1);
        return normalizeSequenceOptions(new SequenceOptions(
                decodeNullableLong(parts, 0),
                decodeNullableLong(parts, 1),
                decodeNullableLong(parts, 2),
                decodeNullableLong(parts, 3),
                decodeNullableInteger(parts, 4),
                parts.length > 5 && Boolean.parseBoolean(parts[5])));
    }

    private static String serializeParameters(List<RoutineParameter> parameters) {
        return parameters.stream()
                .map(parameter -> String.join("~",
                        Integer.toString(parameter.ordinal()),
                        Common.Values.encodeString(parameter.name()),
                        parameter.type().name(),
                        parameter.mode().name(),
                        encodeNullableInteger(parameter.precision()),
                        encodeNullableInteger(parameter.scale())))
                .collect(Collectors.joining(","));
    }

    private static List<RoutineParameter> deserializeParameters(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<RoutineParameter> parameters = new ArrayList<>();
        for (String token : text.split(",")) {
            if (token.isBlank()) {
                continue;
            }
            String[] parts = token.split("~", 6);
            parameters.add(new RoutineParameter(
                    Integer.parseInt(parts[0]),
                    Common.Values.decodeString(parts[1]),
                    Common.DataType.valueOf(parts[2]),
                    ParameterMode.valueOf(parts[3]),
                    decodeNullableInteger(parts, 4),
                    decodeNullableInteger(parts, 5)));
        }
        return parameters;
    }

    private static String encodeNullableLong(Long value) {
        return value == null ? "" : value.toString();
    }

    private static String encodeNullableInteger(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static Long decodeNullableLong(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return null;
        }
        return Long.parseLong(parts[index]);
    }

    private static Integer decodeNullableInteger(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return null;
        }
        return Integer.parseInt(parts[index]);
    }

    public static long maxObjectId(CatalogSnapshot snapshot) {
        long max = snapshot.schemasByName().values().stream().map(SchemaDefinition::id).mapToLong(Common.ObjectId::value).max().orElse(0);
        max = Math.max(max, snapshot.tablesById().keySet().stream().mapToLong(Common.ObjectId::value).max().orElse(0));
        max = Math.max(max, snapshot.indexesById().keySet().stream().mapToLong(Common.ObjectId::value).max().orElse(0));
        max = Math.max(max, snapshot.sequencesById().keySet().stream().mapToLong(Common.ObjectId::value).max().orElse(0));
        max = Math.max(max, snapshot.routinesById().keySet().stream().mapToLong(Common.ObjectId::value).max().orElse(0));
        return max;
    }

    public static void writeSnapshot(Path path, CatalogSnapshot snapshot) {
        try {
            Files.createDirectories(path.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("CATALOG|2|" + snapshot.catalogVersion());
            snapshot.schemasByName().values().stream().sorted(Comparator.comparing(SchemaDefinition::name))
                    .forEach(schema -> lines.add("SCHEMA|" + schema.id().value() + "|" + Common.Values.encodeString(schema.name())));
            snapshot.tablesById().values().stream().sorted(Comparator.comparing(table -> table.name().key())).forEach(table -> {
                lines.add("TABLE|" + table.id().value() + "|" + Common.Values.encodeString(table.name().schema()) + "|"
                        + Common.Values.encodeString(table.name().name()));
                table.columns().forEach(column -> lines.add("COLUMN|" + table.id().value() + "|" + column.ordinal() + "|"
                        + Common.Values.encodeString(column.name()) + "|" + column.type().name() + "|" + column.nullable() + "|"
                        + column.primaryKey() + "|" + column.unique() + "|"
                        + Common.Values.encodeString(column.checkExpressionSql() == null ? "" : column.checkExpressionSql()) + "|"
                        + Common.Values.encodeString(serializeIdentity(column.identityDefinition())) + "|"
                        + encodeNullableInteger(column.precision()) + "|"
                        + encodeNullableInteger(column.scale())));
            });
            snapshot.indexesById().values().stream().sorted(Comparator.comparing(IndexDefinition::name)).forEach(index ->
                    lines.add("INDEX|" + index.id().value() + "|" + Common.Values.encodeString(index.name()) + "|" + index.tableId().value()
                            + "|" + index.unique() + "|" + Common.Values.encodeString(String.join(",", index.columns()))));
            snapshot.sequencesById().values().stream().sorted(Comparator.comparing(sequence -> sequence.name().key())).forEach(sequence ->
                    lines.add("SEQUENCE|" + sequence.id().value() + "|" + Common.Values.encodeString(sequence.name().schema()) + "|"
                            + Common.Values.encodeString(sequence.name().name()) + "|"
                            + Common.Values.encodeString(serializeSequenceOptions(sequence.options()))));
            snapshot.routinesById().values().stream().sorted(Comparator.comparing(routine -> routine.name().key())).forEach(routine ->
                    lines.add("ROUTINE|" + routine.id().value() + "|" + Common.Values.encodeString(routine.name().schema()) + "|"
                            + Common.Values.encodeString(routine.name().name()) + "|" + routine.kind().name() + "|"
                            + Common.Values.encodeString(routine.returnType() == null ? "" : routine.returnType().name()) + "|"
                            + encodeNullableInteger(routine.returnPrecision()) + "|"
                            + encodeNullableInteger(routine.returnScale()) + "|"
                            + Common.Values.encodeString(serializeParameters(routine.parameters())) + "|"
                            + Common.Values.encodeString(routine.bodySql() == null ? "" : routine.bodySql())));
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to persist catalog snapshot", exception);
        }
    }

    public static CatalogSnapshot readSnapshot(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            long version = 1;
            Map<String, SchemaDefinition> schemas = new LinkedHashMap<>();
            Map<String, TableDefinition> tablesByName = new LinkedHashMap<>();
            Map<Common.ObjectId, TableDefinition> tablesById = new LinkedHashMap<>();
            List<IndexDefinition> indexes = new ArrayList<>();
            List<SequenceDefinition> sequences = new ArrayList<>();
            List<RoutineDefinition> routines = new ArrayList<>();
            Map<Common.ObjectId, List<ColumnDefinition>> columnsByTable = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", 12);
                switch (parts[0]) {
                    case "CATALOG" -> version = Long.parseLong(parts[2]);
                    case "SCHEMA" -> {
                        SchemaDefinition schema = new SchemaDefinition(new Common.ObjectId(Long.parseLong(parts[1])), Common.Values.decodeString(parts[2]));
                        schemas.put(schema.name(), schema);
                    }
                    case "TABLE" -> {
                        Common.ObjectId tableId = new Common.ObjectId(Long.parseLong(parts[1]));
                        QualifiedName name = new QualifiedName(Common.Values.decodeString(parts[2]), Common.Values.decodeString(parts[3]));
                        columnsByTable.put(tableId, new ArrayList<>());
                        TableDefinition table = new TableDefinition(tableId, name, List.of(), Map.of(), List.of());
                        tablesByName.put(name.key(), table);
                        tablesById.put(tableId, table);
                    }
                    case "COLUMN" -> {
                        Common.ObjectId tableId = new Common.ObjectId(Long.parseLong(parts[1]));
                        columnsByTable.computeIfAbsent(tableId, ignored -> new ArrayList<>())
                                .add(new ColumnDefinition(Integer.parseInt(parts[2]), Common.Values.decodeString(parts[3]),
                                        Common.DataType.valueOf(parts[4]), Boolean.parseBoolean(parts[5]),
                                        Boolean.parseBoolean(parts[6]), Boolean.parseBoolean(parts[7]),
                                        Common.Values.decodeString(parts[8]).isBlank() ? null : Common.Values.decodeString(parts[8]),
                                        parts.length > 9 ? deserializeIdentity(Common.Values.decodeString(parts[9])) : null,
                                        decodeNullableInteger(parts, 10),
                                        decodeNullableInteger(parts, 11)));
                    }
                    case "INDEX" -> indexes.add(new IndexDefinition(new Common.ObjectId(Long.parseLong(parts[1])), Common.Values.decodeString(parts[2]),
                            new Common.ObjectId(Long.parseLong(parts[3])), List.of(Common.Values.decodeString(parts[5]).split(",")),
                            Boolean.parseBoolean(parts[4])));
                    case "SEQUENCE" -> sequences.add(new SequenceDefinition(new Common.ObjectId(Long.parseLong(parts[1])),
                            new QualifiedName(Common.Values.decodeString(parts[2]), Common.Values.decodeString(parts[3])),
                            deserializeSequenceOptions(Common.Values.decodeString(parts[4]))));
                    case "ROUTINE" -> routines.add(new RoutineDefinition(new Common.ObjectId(Long.parseLong(parts[1])),
                            new QualifiedName(Common.Values.decodeString(parts[2]), Common.Values.decodeString(parts[3])),
                            RoutineKind.valueOf(parts[4]),
                            deserializeParameters(Common.Values.decodeString(parts[8])),
                            Common.Values.decodeString(parts[5]).isBlank() ? null : Common.DataType.valueOf(Common.Values.decodeString(parts[5])),
                            decodeNullableInteger(parts, 6),
                            decodeNullableInteger(parts, 7),
                            Common.Values.decodeString(parts[9])));
                    default -> throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Unknown catalog line: " + line);
                }
            }
            Map<String, TableDefinition> rebuiltTablesByName = new LinkedHashMap<>();
            Map<Common.ObjectId, TableDefinition> rebuiltTablesById = new LinkedHashMap<>();
            for (TableDefinition table : tablesById.values()) {
                List<ColumnDefinition> columns = columnsByTable.getOrDefault(table.id(), List.of()).stream()
                        .sorted(Comparator.comparingInt(ColumnDefinition::ordinal)).toList();
                Map<String, Integer> ordinals = new LinkedHashMap<>();
                for (ColumnDefinition column : columns) {
                    ordinals.put(column.name().toLowerCase(), column.ordinal());
                }
                List<Common.ObjectId> indexIds = indexes.stream().filter(index -> index.tableId().equals(table.id())).map(IndexDefinition::id).toList();
                TableDefinition rebuilt = new TableDefinition(table.id(), table.name(), columns, ordinals, indexIds);
                rebuiltTablesByName.put(rebuilt.name().key(), rebuilt);
                rebuiltTablesById.put(rebuilt.id(), rebuilt);
            }
            Map<String, IndexDefinition> indexesByName = new LinkedHashMap<>();
            Map<Common.ObjectId, IndexDefinition> indexesById = new LinkedHashMap<>();
            for (IndexDefinition index : indexes) {
                indexesByName.put(index.name(), index);
                indexesById.put(index.id(), index);
            }
            Map<String, SequenceDefinition> sequencesByName = new LinkedHashMap<>();
            Map<Common.ObjectId, SequenceDefinition> sequencesById = new LinkedHashMap<>();
            for (SequenceDefinition sequence : sequences) {
                sequencesByName.put(sequence.name().key(), sequence);
                sequencesById.put(sequence.id(), sequence);
            }
            Map<String, RoutineDefinition> routinesByName = new LinkedHashMap<>();
            Map<Common.ObjectId, RoutineDefinition> routinesById = new LinkedHashMap<>();
            for (RoutineDefinition routine : routines) {
                routinesByName.put(routine.name().key(), routine);
                routinesById.put(routine.id(), routine);
            }
            return new CatalogSnapshot(version, schemas, rebuiltTablesByName, rebuiltTablesById, indexesByName, indexesById,
                    sequencesByName, sequencesById, routinesByName, routinesById);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR, "Failed to read catalog snapshot", exception);
        }
    }
}
