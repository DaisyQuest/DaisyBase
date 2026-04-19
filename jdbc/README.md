# JDBC Module

This module provides the JavaDB JDBC 4.3 driver for both embedded and remote access.

## Supported URLs

- `jdbc:javadb:embedded:/path/to/db-home`
- `jdbc:javadb:remote://host:15432`

Supported URL properties:

- `user`
- `password`
- `clientName`
- `checkpointInterval`
- `strictDurability`
- `socketTimeoutMillis`
- `connectTimeoutMillis`
- `maxFrameBytes`

## Embedded Example

```java
Class.forName("dev.javadb.jdbc.JavaDbDriver");

try (Connection connection = DriverManager.getConnection("jdbc:javadb:embedded:/var/lib/javadb")) {
    try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL)");
    }
}
```

## Remote Example

```java
Properties properties = new Properties();
properties.setProperty("socketTimeoutMillis", "5000");

try (Connection connection = DriverManager.getConnection("jdbc:javadb:remote://127.0.0.1:15432", properties)) {
    connection.setAutoCommit(false);
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO users VALUES (?, ?)")) {
        insert.setInt(1, 1);
        insert.setString(2, "Ada");
        insert.executeUpdate();
    }
    connection.commit();
}
```

## Supported JDBC Surface

- `Driver`, `DataSource`, `XADataSource`, `Connection`, `Statement`, `PreparedStatement`, `CallableStatement`
- `ResultSet` via disconnected cached row sets, including updatable single-table primary-key queries
- transaction control with auto-commit, explicit commit/rollback, and savepoints
- durable prepared-branch XA coordination with recovery scans and two-phase commit/rollback
- metadata queries for schemas, tables, columns, primary keys, indexes, procedures, functions, and type info
- embedded and remote execution over the JavaDB binary protocol
- remote authentication against catalog-backed users with role/grant authorization
- engine-backed generated keys for identity inserts
- server-side prepare/describe for result and parameter metadata
- callable execution for SQL-backed procedures and functions
- driver-side client info storage, configurable network timeouts, and cooperative statement cancel/query-timeout
- disconnected `Connection.createClob/createNClob/createBlob/createSQLXML/createArrayOf/createStruct` wrappers
- text-backed `Clob`/`NClob`/`SQLXML` parameter binding and result/output accessors over `TEXT` values
- native engine-backed `Blob`/`Array`/`Struct`/`Ref`/`RowId`/`SQLXML` parameter and result handling when the target column/type is native, with deterministic `TEXT` encodings retained for compatibility columns

## Current Limits

- updatable result sets are intentionally bounded to simple single-table `SELECT` statements with direct column projections and primary-key coverage
- `Array.getResultSet(...)`, custom type maps, and typed locator semantics remain unsupported
- XA remains bounded to one active branch per physical JavaDB connection/session; interleaved multi-branch work on one handle is still unsupported
- authentication/authorization is catalog-backed, but schema/object ownership semantics are still coarse and currently enforced through explicit grants rather than a richer SQL security model
- metadata coverage is intentionally bounded to engine features that actually exist today

See:

- `C:\Users\tabur\IdeaProjects\JavaDatabase\docs\jdbc-driver-spec.md`
- `C:\Users\tabur\IdeaProjects\JavaDatabase\docs\jdbc-conformance-review.md`
- `C:\Users\tabur\IdeaProjects\JavaDatabase\docs\jdbc-testing-matrix.md`
