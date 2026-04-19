package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineIntrospection;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.List;

final class JavaDbDatabaseMetaData {
    private JavaDbDatabaseMetaData() {
    }

    static DatabaseMetaData create(JavaDbConnection connection) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                JavaDbDatabaseMetaData.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "getURL" -> connection.jdbcUrl();
                    case "getUserName" -> connection.jdbcUser();
                    case "getDriverName" -> JavaDbDriver.NAME;
                    case "getDriverVersion" -> JavaDbDriver.MAJOR_VERSION + "." + JavaDbDriver.MINOR_VERSION;
                    case "getDriverMajorVersion" -> JavaDbDriver.MAJOR_VERSION;
                    case "getDriverMinorVersion" -> JavaDbDriver.MINOR_VERSION;
                    case "getDatabaseProductName" -> "JavaDB";
                    case "getDatabaseProductVersion" -> "0.1.0-SNAPSHOT";
                    case "getDatabaseMajorVersion" -> 0;
                    case "getDatabaseMinorVersion" -> 1;
                    case "getJDBCMajorVersion" -> 4;
                    case "getJDBCMinorVersion" -> 3;
                    case "supportsTransactions", "supportsSavepoints", "supportsBatchUpdates" -> true;
                    case "supportsResultSetType" -> {
                        int type = (int) args[0];
                        yield type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
                    }
                    case "supportsResultSetConcurrency" -> {
                        int type = (int) args[0];
                        int concurrency = (int) args[1];
                        yield (type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE)
                                && (concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
                    }
                    case "supportsDataDefinitionAndDataManipulationTransactions" -> true;
                    case "supportsDataManipulationTransactionsOnly" -> false;
                    case "supportsANSI92EntryLevelSQL", "supportsANSI92IntermediateSQL", "supportsANSI92FullSQL" -> false;
                    case "supportsMixedCaseIdentifiers" -> false;
                    case "storesLowerCaseIdentifiers" -> true;
                    case "storesUpperCaseIdentifiers", "storesMixedCaseIdentifiers", "storesUpperCaseQuotedIdentifiers" -> false;
                    case "storesMixedCaseQuotedIdentifiers" -> true;
                    case "allProceduresAreCallable", "supportsStoredFunctionsUsingCallSyntax" -> true;
                    case "allTablesAreSelectable" -> true;
                    case "nullsAreSortedHigh", "nullsAreSortedLow", "nullsAreSortedAtStart", "nullsAreSortedAtEnd" -> false;
                    case "usesLocalFiles" -> connection.jdbcUrl().startsWith("jdbc:javadb:embedded:");
                    case "usesLocalFilePerTable" -> false;
                    case "getIdentifierQuoteString" -> "\"";
                    case "getSearchStringEscape" -> "\\";
                    case "getSQLKeywords" -> "";
                    case "getNumericFunctions" -> "ABS";
                    case "getStringFunctions" -> "LOWER,UPPER,LENGTH,TRIM,SUBSTR,SUBSTRING,REPLACE,COALESCE,NVL";
                    case "getSystemFunctions", "getTimeDateFunctions" -> "";
                    case "getCatalogSeparator" -> ".";
                    case "isCatalogAtStart" -> false;
                    case "getCatalogTerm" -> "catalog";
                    case "getSchemaTerm" -> "schema";
                    case "getProcedureTerm" -> "procedure";
                    case "getExtraNameCharacters" -> "";
                    case "supportsCatalogsInDataManipulation", "supportsCatalogsInTableDefinitions",
                            "supportsCatalogsInIndexDefinitions", "supportsCatalogsInPrivilegeDefinitions",
                            "supportsCatalogsInProcedureCalls", "supportsCatalogsInSchemaDefinitions" -> false;
                    case "supportsSchemasInDataManipulation", "supportsSchemasInTableDefinitions",
                            "supportsSchemasInIndexDefinitions" -> true;
                    case "supportsSchemasInProcedureCalls", "supportsSchemasInPrivilegeDefinitions" -> false;
                    case "getDefaultTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "supportsTransactionIsolationLevel" -> {
                        int level = (int) args[0];
                        yield level == Connection.TRANSACTION_READ_UNCOMMITTED
                                || level == Connection.TRANSACTION_READ_COMMITTED
                                || level == Connection.TRANSACTION_REPEATABLE_READ
                                || level == Connection.TRANSACTION_SERIALIZABLE;
                    }
                    case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                    case "supportsResultSetHoldability" -> (int) args[0] == ResultSet.CLOSE_CURSORS_AT_COMMIT;
                    case "getRowIdLifetime" -> RowIdLifetime.ROWID_UNSUPPORTED;
                    case "supportsGetGeneratedKeys", "supportsNamedParameters",
                            "supportsMultipleResultSets", "supportsMultipleOpenResults" -> true;
                    case "generatedKeyAlwaysReturned" -> false;
                    case "supportsLikeEscapeClause", "supportsNonNullableColumns" -> true;
                    case "nullPlusNonNullIsNull" -> true;
                    case "locatorsUpdateCopy", "supportsStatementPooling", "autoCommitFailureClosesAllResultSets" -> false;
                    case "getSchemas" -> {
                        String schemaPattern = args != null && args.length > 1 ? (String) args[1] : "%";
                        yield connection.metadata(EngineIntrospection.MetadataQuery.SCHEMAS, List.of(schemaPattern == null ? "%" : schemaPattern));
                    }
                    case "getCatalogs" -> JavaDbResultSets.empty("TABLE_CAT");
                    case "getTableTypes" -> tableTypes();
                    case "getTypeInfo" -> typeInfo();
                    case "getClientInfoProperties" -> clientInfoProperties();
                    case "getTables" -> connection.metadata(EngineIntrospection.MetadataQuery.TABLES, List.of(
                            (String) args[1], (String) args[2], firstType(args[3])));
                    case "getColumns" -> connection.metadata(EngineIntrospection.MetadataQuery.COLUMNS, List.of(
                            (String) args[1], (String) args[2], (String) args[3]));
                    case "getPrimaryKeys" -> connection.metadata(EngineIntrospection.MetadataQuery.PRIMARY_KEYS, List.of(
                            (String) args[1], (String) args[2]));
                    case "getIndexInfo" -> connection.metadata(EngineIntrospection.MetadataQuery.INDEX_INFO, List.of(
                            (String) args[1], (String) args[2], String.valueOf((boolean) args[3])));
                    case "getProcedures" -> connection.metadata(EngineIntrospection.MetadataQuery.PROCEDURES, List.of(
                            (String) args[1], (String) args[2]));
                    case "getProcedureColumns" -> connection.metadata(EngineIntrospection.MetadataQuery.PROCEDURE_COLUMNS, List.of(
                            (String) args[1], (String) args[2], (String) args[3]));
                    case "getFunctions" -> connection.metadata(EngineIntrospection.MetadataQuery.FUNCTIONS, List.of(
                            (String) args[1], (String) args[2]));
                    case "getFunctionColumns" -> connection.metadata(EngineIntrospection.MetadataQuery.FUNCTION_COLUMNS, List.of(
                            (String) args[1], (String) args[2], (String) args[3]));
                    case "getUDTs" -> emptyMetadata("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME",
                            "DATA_TYPE", "REMARKS", "BASE_TYPE");
                    case "getSuperTypes" -> emptyMetadata("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT",
                            "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
                    case "getSuperTables" -> emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                            "SUPERTABLE_NAME");
                    case "getAttributes" -> emptyMetadata("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE",
                            "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
                            "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                            "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                            "SOURCE_DATA_TYPE");
                    case "getTablePrivileges" -> emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                            "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
                    case "getColumnPrivileges" -> emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                            "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
                    case "getBestRowIdentifier" -> emptyMetadata("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
                    case "getVersionColumns" -> emptyMetadata("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
                    case "getPseudoColumns" -> emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                            "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE");
                    case "unwrap" -> {
                        Class<?> iface = (Class<?>) args[0];
                        if (iface.isInstance(proxy)) {
                            yield iface.cast(proxy);
                        }
                        throw new SQLException("Not a wrapper for " + iface.getName());
                    }
                    case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                    case "toString" -> "JavaDbDatabaseMetaData[" + connection.jdbcUrl() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType(), method.getName());
                });
    }

    private static String firstType(Object argument) {
        if (!(argument instanceof String[] values) || values.length == 0) {
            return "TABLE";
        }
        return values[0];
    }

    private static ResultSet tableTypes() throws SQLException {
        return JavaDbResultSets.fromTupleBatch(new Common.TupleBatch(
                List.of(new Common.ResultColumn("TABLE_TYPE", Common.DataType.TEXT)),
                List.of(new Common.ResultRow(List.of(Common.Value.text("TABLE"))))), 0);
    }

    private static ResultSet typeInfo() throws SQLException {
        return JavaDbResultSets.fromTupleBatch(new Common.TupleBatch(
                List.of(
                        new Common.ResultColumn("TYPE_NAME", Common.DataType.TEXT),
                        new Common.ResultColumn("DATA_TYPE", Common.DataType.INTEGER),
                        new Common.ResultColumn("PRECISION", Common.DataType.INTEGER),
                        new Common.ResultColumn("LITERAL_PREFIX", Common.DataType.TEXT),
                        new Common.ResultColumn("LITERAL_SUFFIX", Common.DataType.TEXT),
                        new Common.ResultColumn("CREATE_PARAMS", Common.DataType.TEXT),
                        new Common.ResultColumn("NULLABLE", Common.DataType.INTEGER),
                        new Common.ResultColumn("CASE_SENSITIVE", Common.DataType.BOOLEAN),
                        new Common.ResultColumn("SEARCHABLE", Common.DataType.INTEGER),
                        new Common.ResultColumn("UNSIGNED_ATTRIBUTE", Common.DataType.BOOLEAN),
                        new Common.ResultColumn("FIXED_PREC_SCALE", Common.DataType.BOOLEAN),
                        new Common.ResultColumn("AUTO_INCREMENT", Common.DataType.BOOLEAN),
                        new Common.ResultColumn("LOCAL_TYPE_NAME", Common.DataType.TEXT),
                        new Common.ResultColumn("MINIMUM_SCALE", Common.DataType.INTEGER),
                        new Common.ResultColumn("MAXIMUM_SCALE", Common.DataType.INTEGER),
                        new Common.ResultColumn("SQL_DATA_TYPE", Common.DataType.INTEGER),
                        new Common.ResultColumn("SQL_DATETIME_SUB", Common.DataType.INTEGER),
                        new Common.ResultColumn("NUM_PREC_RADIX", Common.DataType.INTEGER)
                ),
                List.of(
                        typeInfoRow("INTEGER", java.sql.Types.INTEGER, 10, false),
                        typeInfoRow("BIGINT", java.sql.Types.BIGINT, 19, false),
                        typeInfoRow("DECIMAL", java.sql.Types.DECIMAL, 38, false),
                        typeInfoRow("BOOLEAN", java.sql.Types.BOOLEAN, 1, false),
                        typeInfoRow("TEXT", java.sql.Types.VARCHAR, 32_767, true),
                        typeInfoRow("DATE", java.sql.Types.DATE, 10, false),
                        typeInfoRow("TIME", java.sql.Types.TIME, 12, false),
                        typeInfoRow("TIMESTAMP", java.sql.Types.TIMESTAMP, 29, false)
                )), 0);
    }

    private static Common.ResultRow typeInfoRow(String name, int jdbcType, int precision, boolean caseSensitive) {
        return new Common.ResultRow(List.of(
                Common.Value.text(name),
                Common.Value.integer(jdbcType),
                Common.Value.integer(precision),
                Common.Value.text("'"),
                Common.Value.text("'"),
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.integer(DatabaseMetaData.typeNullable),
                Common.Value.bool(caseSensitive),
                Common.Value.integer(DatabaseMetaData.typeSearchable),
                Common.Value.bool(false),
                Common.Value.bool(false),
                Common.Value.bool(false),
                Common.Value.nullValue(Common.DataType.TEXT),
                Common.Value.integer(0),
                Common.Value.integer(0),
                Common.Value.nullValue(Common.DataType.INTEGER),
                Common.Value.nullValue(Common.DataType.INTEGER),
                Common.Value.integer(10)
        ));
    }

    private static ResultSet clientInfoProperties() throws SQLException {
        return emptyMetadata("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    private static ResultSet emptyMetadata(String... columns) throws SQLException {
        return JavaDbResultSets.empty(columns);
    }

    private static Object defaultValue(Class<?> returnType, String methodName) throws SQLException {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType == ResultSet.class) {
            throw new SQLException("Unsupported DatabaseMetaData result set method: " + methodName);
        }
        if (returnType == RowIdLifetime.class) {
            return RowIdLifetime.ROWID_UNSUPPORTED;
        }
        if (returnType == Connection.class) {
            throw new SQLException("Unsupported DatabaseMetaData connection method: " + methodName);
        }
        return null;
    }
}
