package dev.daisybase.orm;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class DaisyBaseOrmIntrospector {
    private DaisyBaseOrmIntrospector() {
    }

    public static SchemaModel inspect(DataSource dataSource, String schemaPattern, String tablePattern) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            List<TableModel> tables = new ArrayList<>();
            try (ResultSet tableRows = metaData.getTables(null, schemaPattern, tablePattern, new String[]{"TABLE"})) {
                while (tableRows.next()) {
                    String schema = tableRows.getString("TABLE_SCHEM");
                    String table = tableRows.getString("TABLE_NAME");
                    Map<String, Integer> pkOrder = primaryKeyOrder(metaData, schema, table);
                    List<ColumnModel> columns = columns(metaData, schema, table, pkOrder);
                    tables.add(new TableModel(schema, table, DaisyBaseEntityMetadata.toClassCase(table), columns));
                }
            }
            tables.sort(Comparator.comparing(TableModel::qualifiedName));
            return new SchemaModel(tables);
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Failed to introspect schema", exception);
        }
    }

    private static Map<String, Integer> primaryKeyOrder(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        Map<String, Integer> order = new LinkedHashMap<>();
        try (ResultSet primaryKeys = metaData.getPrimaryKeys(null, schema, table)) {
            while (primaryKeys.next()) {
                order.put(primaryKeys.getString("COLUMN_NAME"), primaryKeys.getInt("KEY_SEQ"));
            }
        }
        return order;
    }

    private static List<ColumnModel> columns(DatabaseMetaData metaData, String schema, String table,
                                             Map<String, Integer> pkOrder) throws SQLException {
        List<ColumnModel> columns = new ArrayList<>();
        try (ResultSet columnRows = metaData.getColumns(null, schema, table, "%")) {
            while (columnRows.next()) {
                String columnName = columnRows.getString("COLUMN_NAME");
                int jdbcType = columnRows.getInt("DATA_TYPE");
                String typeName = columnRows.getString("TYPE_NAME");
                Integer size = nullableInt(columnRows, "COLUMN_SIZE");
                Integer scale = nullableInt(columnRows, "DECIMAL_DIGITS");
                boolean nullable = "YES".equalsIgnoreCase(columnRows.getString("IS_NULLABLE"));
                boolean autoIncrement = "YES".equalsIgnoreCase(columnRows.getString("IS_AUTOINCREMENT"));
                int ordinal = columnRows.getInt("ORDINAL_POSITION");
                columns.add(new ColumnModel(
                        columnName,
                        DaisyBaseEntityMetadata.toPropertyCase(columnName),
                        javaTypeName(jdbcType),
                        jdbcType,
                        typeName == null ? "UNKNOWN" : typeName,
                        size,
                        scale,
                        nullable,
                        autoIncrement,
                        pkOrder.containsKey(columnName),
                        ordinal
                ));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnModel::ordinalPosition));
        return columns;
    }

    private static Integer nullableInt(ResultSet resultSet, String columnLabel) throws SQLException {
        int value = resultSet.getInt(columnLabel);
        return resultSet.wasNull() ? null : value;
    }

    static String javaTypeName(int jdbcType) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "Integer";
            case Types.BIGINT -> "Long";
            case Types.BOOLEAN, Types.BIT -> "Boolean";
            case Types.NUMERIC, Types.DECIMAL -> "BigDecimal";
            case Types.DATE -> "LocalDate";
            case Types.TIME -> "LocalTime";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "LocalDateTime";
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "byte[]";
            default -> "String";
        };
    }

    public record SchemaModel(List<TableModel> tables) {
        public SchemaModel {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record TableModel(String schemaName, String tableName, String className, List<ColumnModel> columns) {
        public TableModel {
            schemaName = schemaName == null || schemaName.isBlank() ? "public" : schemaName;
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        public String qualifiedName() {
            return schemaName + "." + tableName;
        }

        public ColumnModel idColumn() {
            return columns.stream().filter(ColumnModel::primaryKey).findFirst()
                    .orElseThrow(() -> new DaisyBaseOrmException("Table has no primary key for ORM generation: " + qualifiedName()));
        }

        public Set<String> imports() {
            Set<String> imports = new TreeSet<>();
            for (ColumnModel column : columns) {
                switch (column.javaTypeName()) {
                    case "BigDecimal" -> imports.add("java.math.BigDecimal");
                    case "LocalDate" -> imports.add("java.time.LocalDate");
                    case "LocalTime" -> imports.add("java.time.LocalTime");
                    case "LocalDateTime" -> imports.add("java.time.LocalDateTime");
                    default -> {
                    }
                }
            }
            return imports;
        }
    }

    public record ColumnModel(String columnName, String propertyName, String javaTypeName, int jdbcType,
                              String sqlTypeName, Integer size, Integer scale, boolean nullable,
                              boolean autoIncrement, boolean primaryKey, int ordinalPosition) {
    }
}
