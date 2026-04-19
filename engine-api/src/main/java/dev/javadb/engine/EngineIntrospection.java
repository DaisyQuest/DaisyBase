package dev.javadb.engine;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EngineIntrospection {
    private EngineIntrospection() {
    }

    public enum MetadataQuery {
        SCHEMAS,
        TABLES,
        COLUMNS,
        PRIMARY_KEYS,
        INDEX_INFO,
        PROCEDURES,
        PROCEDURE_COLUMNS,
        FUNCTIONS,
        FUNCTION_COLUMNS,
        XA_RECOVER
    }

    public static Common.TupleBatch query(EngineApi.DatabaseEngine engine, MetadataQuery query, List<String> arguments) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(arguments, "arguments");
        Catalog.CatalogSnapshot snapshot = snapshot(engine);
        return switch (query) {
            case SCHEMAS -> schemas(snapshot, arg(arguments, 0));
            case TABLES -> tables(snapshot, arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case COLUMNS -> columns(snapshot, arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case PRIMARY_KEYS -> primaryKeys(snapshot, arg(arguments, 0), arg(arguments, 1));
            case INDEX_INFO -> indexInfo(snapshot, arg(arguments, 0), arg(arguments, 1), Boolean.parseBoolean(arg(arguments, 2)));
            case PROCEDURES -> procedures(snapshot, arg(arguments, 0), arg(arguments, 1));
            case PROCEDURE_COLUMNS -> procedureColumns(snapshot, arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case FUNCTIONS -> functions(snapshot, arg(arguments, 0), arg(arguments, 1));
            case FUNCTION_COLUMNS -> functionColumns(snapshot, arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case XA_RECOVER -> xaRecover(engine);
        };
    }

    private static Catalog.CatalogSnapshot snapshot(EngineApi.DatabaseEngine engine) {
        if (engine instanceof EmbeddedDatabaseEngine embeddedDatabaseEngine) {
            return embeddedDatabaseEngine.catalogSnapshotForIntrospection();
        }
        throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                "Metadata introspection is unsupported for engine type " + engine.getClass().getSimpleName());
    }

    private static Common.TupleBatch xaRecover(EngineApi.DatabaseEngine engine) {
        if (!(engine instanceof EmbeddedDatabaseEngine embeddedDatabaseEngine)) {
            throw new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE,
                    "XA recovery introspection is unsupported for engine type " + engine.getClass().getSimpleName());
        }
        List<Common.ResultRow> rows = embeddedDatabaseEngine.recoverPreparedXa().stream()
                .map(xid -> new Common.ResultRow(List.of(
                        Common.Value.integer(xid.formatId()),
                        Common.Value.blob(xid.globalId()),
                        Common.Value.blob(xid.branchId())
                )))
                .toList();
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("FORMAT_ID", Common.DataType.INTEGER),
                new Common.ResultColumn("GLOBAL_ID", Common.DataType.BLOB),
                new Common.ResultColumn("BRANCH_ID", Common.DataType.BLOB)
        ), rows);
    }

    private static Common.TupleBatch schemas(Catalog.CatalogSnapshot snapshot, String schemaPattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        List<Common.ResultRow> rows = snapshot.schemasByName().values().stream()
                .filter(schema -> matches(schemaMatcher, schema.name()))
                .sorted(Comparator.comparing(Catalog.SchemaDefinition::name))
                .map(schema -> new Common.ResultRow(List.of(
                        Common.Value.text(schema.name()),
                        Common.Value.nullValue(Common.DataType.TEXT)
                )))
                .toList();
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("TABLE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_CATALOG", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch tables(Catalog.CatalogSnapshot snapshot, String schemaPattern,
                                            String tablePattern, String tableType) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern tableMatcher = matcher(tablePattern);
        boolean includeTables = tableType == null || tableType.isBlank() || "TABLE".equalsIgnoreCase(tableType);
        List<Common.ResultRow> rows = includeTables
                ? snapshot.tablesById().values().stream()
                .filter(table -> matches(schemaMatcher, table.name().schema()))
                .filter(table -> matches(tableMatcher, table.name().name()))
                .sorted(Comparator.comparing(table -> table.name().key()))
                .map(table -> new Common.ResultRow(List.of(
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.text(table.name().schema()),
                        Common.Value.text(table.name().name()),
                        Common.Value.text("TABLE"),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT)
                )))
                .toList()
                : List.of();
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("TABLE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_TYPE", Common.DataType.TEXT),
                new Common.ResultColumn("REMARKS", Common.DataType.TEXT),
                new Common.ResultColumn("TYPE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("TYPE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("TYPE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("SELF_REFERENCING_COL_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("REF_GENERATION", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch columns(Catalog.CatalogSnapshot snapshot, String schemaPattern,
                                             String tablePattern, String columnPattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern tableMatcher = matcher(tablePattern);
        Pattern columnMatcher = matcher(columnPattern);
        List<Common.ResultRow> rows = new ArrayList<>();
        snapshot.tablesById().values().stream()
                .filter(table -> matches(schemaMatcher, table.name().schema()))
                .filter(table -> matches(tableMatcher, table.name().name()))
                .sorted(Comparator.comparing(table -> table.name().key()))
                .forEach(table -> table.columns().stream()
                        .filter(column -> matches(columnMatcher, column.name()))
                        .sorted(Comparator.comparingInt(Catalog.ColumnDefinition::ordinal))
                        .forEach(column -> rows.add(new Common.ResultRow(List.of(
                                Common.Value.nullValue(Common.DataType.TEXT),
                                Common.Value.text(table.name().schema()),
                                Common.Value.text(table.name().name()),
                                Common.Value.text(column.name()),
                                Common.Value.integer(jdbcType(column.type())),
                                Common.Value.text(typeName(column.type())),
                                Common.Value.integer(columnSize(column.type(), column.precision())),
                                Common.Value.nullValue(Common.DataType.INTEGER),
                                Common.Value.integer(scale(column.type(), column.scale())),
                                Common.Value.integer(10),
                                Common.Value.integer(column.nullable() ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls),
                                Common.Value.nullValue(Common.DataType.TEXT),
                                Common.Value.nullValue(Common.DataType.TEXT),
                                Common.Value.nullValue(Common.DataType.INTEGER),
                                Common.Value.nullValue(Common.DataType.INTEGER),
                                Common.Value.integer(column.type() == Common.DataType.TEXT ? columnSize(column.type(), column.precision()) : 0),
                                Common.Value.integer(column.ordinal() + 1),
                                Common.Value.text(column.nullable() ? "YES" : "NO"),
                                Common.Value.text(column.identityDefinition() == null ? "NO" : "YES"),
                                Common.Value.text(column.identityDefinition() == null ? "NO" : "YES")
                        )))));
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("TABLE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("DATA_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("TYPE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_SIZE", Common.DataType.INTEGER),
                new Common.ResultColumn("BUFFER_LENGTH", Common.DataType.INTEGER),
                new Common.ResultColumn("DECIMAL_DIGITS", Common.DataType.INTEGER),
                new Common.ResultColumn("NUM_PREC_RADIX", Common.DataType.INTEGER),
                new Common.ResultColumn("NULLABLE", Common.DataType.INTEGER),
                new Common.ResultColumn("REMARKS", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_DEF", Common.DataType.TEXT),
                new Common.ResultColumn("SQL_DATA_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("SQL_DATETIME_SUB", Common.DataType.INTEGER),
                new Common.ResultColumn("CHAR_OCTET_LENGTH", Common.DataType.INTEGER),
                new Common.ResultColumn("ORDINAL_POSITION", Common.DataType.INTEGER),
                new Common.ResultColumn("IS_NULLABLE", Common.DataType.TEXT),
                new Common.ResultColumn("IS_AUTOINCREMENT", Common.DataType.TEXT),
                new Common.ResultColumn("IS_GENERATEDCOLUMN", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch primaryKeys(Catalog.CatalogSnapshot snapshot, String schemaPattern, String tablePattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern tableMatcher = matcher(tablePattern);
        List<Common.ResultRow> rows = new ArrayList<>();
        snapshot.tablesById().values().stream()
                .filter(table -> matches(schemaMatcher, table.name().schema()))
                .filter(table -> matches(tableMatcher, table.name().name()))
                .sorted(Comparator.comparing(table -> table.name().key()))
                .forEach(table -> {
                    int keySequence = 1;
                    for (Catalog.ColumnDefinition column : table.columns()) {
                        if (!column.primaryKey()) {
                            continue;
                        }
                        rows.add(new Common.ResultRow(List.of(
                                Common.Value.nullValue(Common.DataType.TEXT),
                                Common.Value.text(table.name().schema()),
                                Common.Value.text(table.name().name()),
                                Common.Value.text(column.name()),
                                Common.Value.integer(keySequence++),
                                Common.Value.text("pk_" + table.name().name())
                        )));
                    }
                });
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("TABLE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("KEY_SEQ", Common.DataType.INTEGER),
                new Common.ResultColumn("PK_NAME", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch indexInfo(Catalog.CatalogSnapshot snapshot, String schemaPattern,
                                               String tablePattern, boolean uniqueOnly) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern tableMatcher = matcher(tablePattern);
        List<Common.ResultRow> rows = new ArrayList<>();
        snapshot.tablesById().values().stream()
                .filter(table -> matches(schemaMatcher, table.name().schema()))
                .filter(table -> matches(tableMatcher, table.name().name()))
                .sorted(Comparator.comparing(table -> table.name().key()))
                .forEach(table -> table.indexIds().stream()
                        .map(snapshot.indexesById()::get)
                        .filter(Objects::nonNull)
                        .filter(index -> !uniqueOnly || index.unique())
                        .sorted(Comparator.comparing(Catalog.IndexDefinition::name))
                        .forEach(index -> {
                            int ordinal = 1;
                            for (String column : index.columns()) {
                                rows.add(new Common.ResultRow(List.of(
                                        Common.Value.nullValue(Common.DataType.TEXT),
                                        Common.Value.text(table.name().schema()),
                                        Common.Value.text(table.name().name()),
                                        Common.Value.bool(!index.unique()),
                                        Common.Value.nullValue(Common.DataType.TEXT),
                                        Common.Value.text(index.name()),
                                        Common.Value.integer((int) DatabaseMetaData.tableIndexOther),
                                        Common.Value.integer(ordinal++),
                                        Common.Value.text(column),
                                        Common.Value.text("A"),
                                        Common.Value.bigint(0L),
                                        Common.Value.bigint(0L),
                                        Common.Value.nullValue(Common.DataType.TEXT)
                                )));
                            }
                        }));
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("TABLE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("TABLE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("NON_UNIQUE", Common.DataType.BOOLEAN),
                new Common.ResultColumn("INDEX_QUALIFIER", Common.DataType.TEXT),
                new Common.ResultColumn("INDEX_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("ORDINAL_POSITION", Common.DataType.INTEGER),
                new Common.ResultColumn("COLUMN_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("ASC_OR_DESC", Common.DataType.TEXT),
                new Common.ResultColumn("CARDINALITY", Common.DataType.BIGINT),
                new Common.ResultColumn("PAGES", Common.DataType.BIGINT),
                new Common.ResultColumn("FILTER_CONDITION", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch procedures(Catalog.CatalogSnapshot snapshot, String schemaPattern, String routinePattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern routineMatcher = matcher(routinePattern);
        List<Common.ResultRow> rows = snapshot.routinesById().values().stream()
                .filter(routine -> routine.kind() == Catalog.RoutineKind.PROCEDURE)
                .filter(routine -> matches(schemaMatcher, routine.name().schema()))
                .filter(routine -> matches(routineMatcher, routine.name().name()))
                .sorted(Comparator.comparing(routine -> routine.name().key()))
                .map(routine -> new Common.ResultRow(List.of(
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.text(routine.name().schema()),
                        Common.Value.text(routine.name().name()),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.integer(DatabaseMetaData.procedureNoResult),
                        Common.Value.text(routine.name().name())
                )))
                .toList();
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("PROCEDURE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("PROCEDURE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("PROCEDURE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("RESERVED_1", Common.DataType.TEXT),
                new Common.ResultColumn("RESERVED_2", Common.DataType.TEXT),
                new Common.ResultColumn("RESERVED_3", Common.DataType.TEXT),
                new Common.ResultColumn("REMARKS", Common.DataType.TEXT),
                new Common.ResultColumn("PROCEDURE_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("SPECIFIC_NAME", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch procedureColumns(Catalog.CatalogSnapshot snapshot, String schemaPattern,
                                                      String routinePattern, String columnPattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern routineMatcher = matcher(routinePattern);
        Pattern columnMatcher = matcher(columnPattern);
        List<Common.ResultRow> rows = new ArrayList<>();
        snapshot.routinesById().values().stream()
                .filter(routine -> routine.kind() == Catalog.RoutineKind.PROCEDURE)
                .filter(routine -> matches(schemaMatcher, routine.name().schema()))
                .filter(routine -> matches(routineMatcher, routine.name().name()))
                .sorted(Comparator.comparing(routine -> routine.name().key()))
                .forEach(routine -> routine.parameters().stream()
                        .filter(parameter -> matches(columnMatcher, parameter.name()))
                        .sorted(Comparator.comparingInt(Catalog.RoutineParameter::ordinal))
                        .forEach(parameter -> rows.add(routineColumnRow(
                                routine.name().schema(),
                                routine.name().name(),
                                parameter.name(),
                                switch (parameter.mode()) {
                                    case IN -> DatabaseMetaData.procedureColumnIn;
                                    case OUT -> DatabaseMetaData.procedureColumnOut;
                                    case INOUT -> DatabaseMetaData.procedureColumnInOut;
                                },
                                parameter.type(),
                                parameter.precision(),
                                parameter.scale(),
                                parameter.ordinal() + 1,
                                routine.name().name()
                        ))));
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("PROCEDURE_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("PROCEDURE_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("PROCEDURE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("DATA_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("TYPE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("PRECISION", Common.DataType.INTEGER),
                new Common.ResultColumn("LENGTH", Common.DataType.INTEGER),
                new Common.ResultColumn("SCALE", Common.DataType.INTEGER),
                new Common.ResultColumn("RADIX", Common.DataType.INTEGER),
                new Common.ResultColumn("NULLABLE", Common.DataType.INTEGER),
                new Common.ResultColumn("REMARKS", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_DEF", Common.DataType.TEXT),
                new Common.ResultColumn("SQL_DATA_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("SQL_DATETIME_SUB", Common.DataType.INTEGER),
                new Common.ResultColumn("CHAR_OCTET_LENGTH", Common.DataType.INTEGER),
                new Common.ResultColumn("ORDINAL_POSITION", Common.DataType.INTEGER),
                new Common.ResultColumn("IS_NULLABLE", Common.DataType.TEXT),
                new Common.ResultColumn("SPECIFIC_NAME", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch functions(Catalog.CatalogSnapshot snapshot, String schemaPattern, String routinePattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern routineMatcher = matcher(routinePattern);
        List<Common.ResultRow> rows = snapshot.routinesById().values().stream()
                .filter(routine -> routine.kind() == Catalog.RoutineKind.FUNCTION)
                .filter(routine -> matches(schemaMatcher, routine.name().schema()))
                .filter(routine -> matches(routineMatcher, routine.name().name()))
                .sorted(Comparator.comparing(routine -> routine.name().key()))
                .map(routine -> new Common.ResultRow(List.of(
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.text(routine.name().schema()),
                        Common.Value.text(routine.name().name()),
                        Common.Value.nullValue(Common.DataType.TEXT),
                        Common.Value.integer(DatabaseMetaData.functionNoTable),
                        Common.Value.text(routine.name().name())
                )))
                .toList();
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("FUNCTION_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("FUNCTION_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("FUNCTION_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("REMARKS", Common.DataType.TEXT),
                new Common.ResultColumn("FUNCTION_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("SPECIFIC_NAME", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.TupleBatch functionColumns(Catalog.CatalogSnapshot snapshot, String schemaPattern,
                                                     String routinePattern, String columnPattern) {
        Pattern schemaMatcher = matcher(schemaPattern);
        Pattern routineMatcher = matcher(routinePattern);
        Pattern columnMatcher = matcher(columnPattern);
        List<Common.ResultRow> rows = new ArrayList<>();
        snapshot.routinesById().values().stream()
                .filter(routine -> routine.kind() == Catalog.RoutineKind.FUNCTION)
                .filter(routine -> matches(schemaMatcher, routine.name().schema()))
                .filter(routine -> matches(routineMatcher, routine.name().name()))
                .sorted(Comparator.comparing(routine -> routine.name().key()))
                .forEach(routine -> {
                    if (routine.returnType() != null && matches(columnMatcher, "RETURN_VALUE")) {
                        rows.add(functionColumnRow(routine.name().schema(), routine.name().name(), "RETURN_VALUE",
                                DatabaseMetaData.functionReturn, routine.returnType(),
                                routine.returnPrecision(), routine.returnScale(), 0, routine.name().name()));
                    }
                    routine.parameters().stream()
                            .filter(parameter -> matches(columnMatcher, parameter.name()))
                            .sorted(Comparator.comparingInt(Catalog.RoutineParameter::ordinal))
                            .forEach(parameter -> rows.add(functionColumnRow(
                                    routine.name().schema(),
                                    routine.name().name(),
                                    parameter.name(),
                                    switch (parameter.mode()) {
                                        case IN -> DatabaseMetaData.functionColumnIn;
                                        case OUT -> DatabaseMetaData.functionColumnOut;
                                        case INOUT -> DatabaseMetaData.functionColumnInOut;
                                    },
                                    parameter.type(),
                                    parameter.precision(),
                                    parameter.scale(),
                                    parameter.ordinal() + 1,
                                    routine.name().name()
                            )));
                });
        return new Common.TupleBatch(List.of(
                new Common.ResultColumn("FUNCTION_CAT", Common.DataType.TEXT),
                new Common.ResultColumn("FUNCTION_SCHEM", Common.DataType.TEXT),
                new Common.ResultColumn("FUNCTION_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("COLUMN_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("DATA_TYPE", Common.DataType.INTEGER),
                new Common.ResultColumn("TYPE_NAME", Common.DataType.TEXT),
                new Common.ResultColumn("PRECISION", Common.DataType.INTEGER),
                new Common.ResultColumn("LENGTH", Common.DataType.INTEGER),
                new Common.ResultColumn("SCALE", Common.DataType.INTEGER),
                new Common.ResultColumn("RADIX", Common.DataType.INTEGER),
                new Common.ResultColumn("NULLABLE", Common.DataType.INTEGER),
                new Common.ResultColumn("REMARKS", Common.DataType.TEXT),
                new Common.ResultColumn("CHAR_OCTET_LENGTH", Common.DataType.INTEGER),
                new Common.ResultColumn("ORDINAL_POSITION", Common.DataType.INTEGER),
                new Common.ResultColumn("IS_NULLABLE", Common.DataType.TEXT),
                new Common.ResultColumn("SPECIFIC_NAME", Common.DataType.TEXT)
        ), rows);
    }

    private static Common.ResultRow routineColumnRow(String schema, String routineName, String columnName, int columnType,
                                                     Common.DataType type, Integer precision, Integer scale,
                                                     int ordinalPosition, String specificName) {
        return new Common.ResultRow(List.of(
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.text(schema),
                Common.Value.text(routineName),
                Common.Value.text(columnName),
                Common.Value.integer(columnType),
                Common.Value.integer(jdbcType(type)),
                Common.Value.text(typeName(type)),
                Common.Value.integer(columnSize(type, precision)),
                Common.Value.integer(columnSize(type, precision)),
                Common.Value.integer(scale(type, scale)),
                Common.Value.integer(10),
                Common.Value.integer(DatabaseMetaData.procedureNullableUnknown),
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.nullValue(Common.DataType.INTEGER),
                Common.Value.nullValue(Common.DataType.INTEGER),
                Common.Value.integer(type == Common.DataType.TEXT ? columnSize(type, precision) : 0),
                Common.Value.integer(ordinalPosition),
                Common.Value.text("YES"),
                Common.Value.text(specificName)
        ));
    }

    private static Common.ResultRow functionColumnRow(String schema, String routineName, String columnName, int columnType,
                                                      Common.DataType type, Integer precision, Integer scale,
                                                      int ordinalPosition, String specificName) {
        return new Common.ResultRow(List.of(
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.text(schema),
                Common.Value.text(routineName),
                Common.Value.text(columnName),
                Common.Value.integer(columnType),
                Common.Value.integer(jdbcType(type)),
                Common.Value.text(typeName(type)),
                Common.Value.integer(columnSize(type, precision)),
                Common.Value.integer(columnSize(type, precision)),
                Common.Value.integer(scale(type, scale)),
                Common.Value.integer(10),
                Common.Value.integer(DatabaseMetaData.functionNullableUnknown),
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.integer(type == Common.DataType.TEXT ? columnSize(type, precision) : 0),
                Common.Value.integer(ordinalPosition),
                Common.Value.text("YES"),
                Common.Value.text(specificName)
        ));
    }

    private static int jdbcType(Common.DataType type) {
        return switch (type) {
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case BOOLEAN -> Types.BOOLEAN;
            case TEXT -> Types.VARCHAR;
            case BLOB -> Types.BLOB;
            case ARRAY -> Types.ARRAY;
            case STRUCT -> Types.STRUCT;
            case REF -> Types.REF;
            case ROWID -> Types.ROWID;
            case SQLXML -> Types.SQLXML;
            case DECIMAL -> Types.DECIMAL;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
        };
    }

    private static String typeName(Common.DataType type) {
        return switch (type) {
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case BOOLEAN -> "BOOLEAN";
            case TEXT -> "TEXT";
            case BLOB -> "BLOB";
            case ARRAY -> "ARRAY";
            case STRUCT -> "STRUCT";
            case REF -> "REF";
            case ROWID -> "ROWID";
            case SQLXML -> "SQLXML";
            case DECIMAL -> "DECIMAL";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "TIMESTAMP";
        };
    }

    private static int columnSize(Common.DataType type, Integer precision) {
        return switch (type) {
            case INTEGER -> 10;
            case BIGINT -> 19;
            case BOOLEAN -> 1;
            case TEXT -> 32_767;
            case BLOB -> 1_048_576;
            case ARRAY, STRUCT, REF -> 32_767;
            case ROWID -> 256;
            case SQLXML -> 1_048_576;
            case DECIMAL -> precision == null ? type.defaultPrecision() : precision;
            case DATE -> 10;
            case TIME -> 12;
            case TIMESTAMP -> 29;
        };
    }

    private static int scale(Common.DataType type, Integer declaredScale) {
        return switch (type) {
            case DECIMAL -> declaredScale == null ? type.defaultScale() : declaredScale;
            case INTEGER, BIGINT, BOOLEAN, TEXT, BLOB, ARRAY, STRUCT, REF, ROWID, SQLXML, DATE, TIME, TIMESTAMP -> 0;
        };
    }

    private static Pattern matcher(String pattern) {
        if (pattern == null || pattern.isBlank() || "%".equals(pattern)) {
            return null;
        }
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < pattern.length(); index++) {
            char ch = pattern.charAt(index);
            switch (ch) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()*+-?^$|".indexOf(ch) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(ch);
                }
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static boolean matches(Pattern pattern, String value) {
        return pattern == null || pattern.matcher(value).matches();
    }

    private static String arg(List<String> arguments, int index) {
        return index < arguments.size() ? arguments.get(index) : null;
    }
}
