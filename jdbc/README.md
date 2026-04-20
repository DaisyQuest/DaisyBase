# DaisyBase JDBC Module

This module provides the DaisyBase JDBC 4.3 driver for embedded and remote use.

Its job is straightforward:

- make DaisyBase usable from ordinary Java applications
- expose the engine's actual behavior honestly
- preserve a migration path from the older `JavaDb` naming where that costs little and helps users

## URL Forms

Primary URL forms:

- `jdbc:daisybase:embedded:/path/to/db-home`
- `jdbc:daisybase:remote://host:15432`

Compatibility aliases remain accepted:

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
Class.forName("dev.daisybase.jdbc.DaisyBaseDriver");

try (Connection connection = DriverManager.getConnection("jdbc:daisybase:embedded:/var/lib/daisybase")) {
    try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL)");
    }
}
```

Migration shim, if older code still refers to the pre-rebrand class name:

```java
Class.forName("dev.javadb.jdbc.JavaDbDriver");
```

## Remote Example

```java
Properties properties = new Properties();
properties.setProperty("socketTimeoutMillis", "5000");

try (Connection connection = DriverManager.getConnection("jdbc:daisybase:remote://127.0.0.1:15432", properties)) {
    connection.setAutoCommit(false);
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO users VALUES (?, ?)")) {
        insert.setInt(1, 1);
        insert.setString(2, "Ada");
        insert.executeUpdate();
    }
    connection.commit();
}
```

## Supported Surface

The driver currently supports:

- `Driver`, `DataSource`, `XADataSource`, `Connection`, `Statement`, `PreparedStatement`, `CallableStatement`
- disconnected `ResultSet` handling, including bounded updatable result sets for simple single-table primary-key queries
- auto-commit, explicit commit/rollback, and savepoints
- durable prepared-branch XA with recovery scans and two-phase commit/rollback
- metadata queries for schemas, tables, columns, primary keys, indexes, procedures, functions, and type information
- embedded and remote execution over the DaisyBase binary protocol
- remote authentication against catalog-backed users
- engine-backed generated keys for identity inserts
- server-side prepare/describe for result and parameter metadata
- callable execution for SQL-backed procedures and functions
- configurable network timeouts and cooperative cancel/query-timeout handling
- JDBC object wrappers for `Clob`, `NClob`, `Blob`, `SQLXML`, `Array`, `Struct`, `Ref`, and `RowId`

## Boundaries

This driver is meant to be useful, not mystical.

Important limits remain:

- updatable result sets are intentionally limited to simple single-table `SELECT` statements with direct column projections and primary-key coverage
- `Array.getResultSet(...)`, custom type maps, and typed locator semantics are still unsupported
- XA remains bounded to one active branch per physical DaisyBase connection/session
- security is catalog-backed, but object ownership and richer SQL authorization semantics are still narrower than those of a mature commercial system
- metadata coverage follows real engine capability rather than attempting to imitate unsupported features

## Further Reading

- [JDBC Driver Spec](../docs/jdbc-driver-spec.md)
- [JDBC Conformance Review](../docs/jdbc-conformance-review.md)
- [JDBC Testing Matrix](../docs/jdbc-testing-matrix.md)
