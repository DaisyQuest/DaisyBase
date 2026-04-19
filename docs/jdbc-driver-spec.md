# JavaDB JDBC Driver Specification

## 1. Objective

Provide a production-usable JDBC 4.3 driver for JavaDB that supports:

- embedded access to the in-process engine
- remote access to the existing JavaDB protocol server
- `DriverManager`, `DataSource`, and bounded `XADataSource` integration
- JDBC statements, prepared statements, callable statements, result sets, transaction control, savepoints, generated keys, and metadata for the supported engine surface

The driver must be explicit about unsupported behavior and must not silently emulate semantics that the engine does not provide.

## 2. Module Layout

Create a dedicated Gradle module:

- `:jdbc`

Package layout:

- `dev.javadb.jdbc`
  - URL parsing and driver bootstrap
  - JDBC facade types
  - transport abstraction
  - prepared-statement rendering
  - exception mapping
  - metadata materialization

## 3. URL Grammar

Supported JDBC URLs:

- Embedded:
  - `jdbc:javadb:embedded:/absolute/path/to/db-home`
  - `jdbc:javadb:embedded:relative/path`
- Remote:
  - `jdbc:javadb:remote://host`
  - `jdbc:javadb:remote://host:15432`

Recognized properties:

- Common:
  - `user`
  - `password`
  - `clientName`
- Embedded:
  - `checkpointInterval`
  - `strictDurability`
- Remote:
  - `socketTimeoutMillis`
  - `connectTimeoutMillis`
  - `maxFrameBytes`

Current authentication semantics:

- `user` and `password` are accepted for JDBC compatibility.
- Embedded mode ignores them.
- Remote mode sends them during the binary-protocol handshake.
- The server may require one configured static user/password pair.
- `setClientInfo(...)` values are stored driver-side for framework compatibility.

## 4. Driver Contract

### 4.1 Driver Registration

- The driver class is `dev.javadb.jdbc.JavaDbDriver`.
- The driver is auto-registered through `META-INF/services/java.sql.Driver`.
- Manual registration through `Class.forName(...)` remains supported.

### 4.2 DataSource

- `dev.javadb.jdbc.JavaDbDataSource` implements `javax.sql.DataSource`.
- It supports both embedded and remote URLs.
- It is serializable only if all stored configuration values are serializable primitives/strings.

### 4.3 XADataSource

- `dev.javadb.jdbc.JavaDbXADataSource` implements `javax.sql.XADataSource`.
- It exposes one `XAResource` per underlying JavaDB connection.
- The XA surface is intentionally bounded to single-branch coordination over one live JavaDB session.

## 5. Connection Semantics

### 5.1 Auto-commit

- Default is `true`.
- With auto-commit `true`, each JDBC execution is one engine statement batch.
- With auto-commit `false`, the driver opens an explicit engine transaction lazily on the first mutating or querying call that requires one.

### 5.2 Isolation Levels

Mappings:

- `TRANSACTION_READ_COMMITTED` -> `READ COMMITTED`
- `TRANSACTION_REPEATABLE_READ` -> `REPEATABLE READ`
- `TRANSACTION_SERIALIZABLE` -> `SERIALIZABLE`
- `TRANSACTION_READ_UNCOMMITTED` is accepted and mapped to `READ COMMITTED`

### 5.3 Savepoints

- Savepoints are supported for both embedded and remote connections.
- Savepoint names are driver-generated unless the caller provides one explicitly.

### 5.4 Schema and Catalog

- `getCatalog()` returns `null`.
- `setCatalog(...)` is ignored unless the caller supplies `null` or empty string.
- `getSchema()` returns `public`.
- `setSchema(...)` only accepts `null`, empty string, or `public`.

## 6. Statement Semantics

### 6.1 Statement

Supported:

- `execute`
- `executeQuery`
- `executeUpdate`
- `executeLargeUpdate`
- `addBatch`
- `clearBatch`
- `executeBatch`
- `executeLargeBatch`
- `getResultSet`
- `getUpdateCount`
- `getLargeUpdateCount`
- `getMoreResults`
- `getGeneratedKeys` for identity-backed inserts
- `closeOnCompletion`

Behavior:

- Multi-statement SQL text is allowed for plain `Statement`.
- The driver exposes sequential statement results through `getMoreResults()`.
- Result sets are materialized into disconnected cached row sets.

### 6.2 PreparedStatement

Prepared statements use server-side prepare/describe for statement identity and result metadata, while parameter values are still transported as rendered SQL literals.

Rules:

- `?` placeholders are recognized only outside string literals.
- Every placeholder must be bound before execution.
- Supported bind methods:
  - `setNull`
  - `setBoolean`
  - `setInt`
  - `setLong`
  - `setString`
  - `setDate`
  - `setTime`
  - `setTimestamp`
  - `setURL`
  - `setAsciiStream`
  - `setBinaryStream`
  - `setCharacterStream`
  - `setObject`
  - `setBigDecimal`
  - `setBytes`
  - `setBlob`
  - `setClob`
  - `setNClob`
  - `setSQLXML`
  - `setArray`
  - `setRef`
  - `setRowId`
- Binary, array, struct, ref, row-id, and XML bindings are currently encoded through `TEXT` literals rather than native engine types.

Prepared-statement scope:

- Intended for a single SQL statement template.
- Multi-statement templates are rejected.

## 7. Result Sets

- Result sets are scroll-insensitive.
- Concurrency is:
  - `CONCUR_READ_ONLY` for general queries
  - `CONCUR_UPDATABLE` for eligible single-table primary-key-backed queries with direct column projections
- Holdability is `CLOSE_CURSORS_AT_COMMIT`.
- Column lookup supports both 1-based index and case-insensitive label matching.
- JDBC object accessors over `TEXT` are supported for:
  - `Clob`
  - `NClob`
  - `SQLXML`
  - `Blob`
  - `Array`
  - `Struct`
  - `Ref`
  - `RowId`

Type mappings:

- `INTEGER` -> JDBC `INTEGER`
- `BIGINT` -> JDBC `BIGINT`
- `BOOLEAN` -> JDBC `BOOLEAN`
- `TEXT` -> JDBC `VARCHAR`
- `DECIMAL` -> JDBC `DECIMAL`
- `DATE` -> JDBC `DATE`
- `TIME` -> JDBC `TIME`
- `TIMESTAMP` -> JDBC `TIMESTAMP`

## 8. Metadata

Supported metadata methods:

- `getSchemas`
- `getTables`
- `getColumns`
- `getPrimaryKeys`
- `getIndexInfo`
- `getProcedures`
- `getProcedureColumns`
- `getFunctions`
- `getFunctionColumns`
- `getTypeInfo`
- `getClientInfoProperties`
- core capability and version methods required by common frameworks

Metadata visibility:

- Metadata reflects committed engine state.
- Uncommitted DDL visibility through JDBC metadata is not guaranteed.

## 9. Transport Model

### 9.1 Embedded

- Embedded transport uses `EmbeddedDatabaseEngine.open(...)`.
- A JDBC connection owns exactly one engine session.

### 9.2 Remote

- Remote transport uses the JavaDB binary framed protocol.
- The protocol is extended to support:
  - transaction control requests
  - metadata requests
  - prepare/describe requests
  - prepared execution and prepared statement close
  - cooperative cancellation requests
  - optional credential exchange during handshake

## 10. Exception Mapping

Map engine errors deterministically:

- `PARSE_ERROR`, `SEMANTIC_ERROR` -> `SQLSyntaxErrorException`
- `CONSTRAINT_VIOLATION` -> `SQLIntegrityConstraintViolationException`
- `TRANSACTION_CONFLICT` -> `SQLTransactionRollbackException`
- `UNSUPPORTED_FEATURE` -> `SQLFeatureNotSupportedException`
- `STORAGE_ERROR` -> `SQLNonTransientException`
- `INTERNAL_ERROR` -> `SQLNonTransientException`

## 11. Unsupported JDBC Features

Fail explicitly with `SQLFeatureNotSupportedException`:

- live cursor semantics beyond disconnected cached row sets
- engine-native large objects (`Blob`, `Clob`, `NClob`, `SQLXML`)
- engine-native arrays, structs, refs, and row ids
- custom SQL type maps and `Array.getResultSet(...)`
- multiple open concurrent live cursors on one statement object
- crash-recoverable distributed XA recovery

## 12. Testing Requirements

Required automated coverage:

- URL parsing
- driver registration
- embedded connection lifecycle
- remote connection lifecycle
- transaction control and savepoints
- prepared statement parameter rendering
- callable statement execution and parameter metadata
- metadata result correctness
- generated key materialization
- timeout and cancel behavior
- exception mapping
- statement multi-result traversal
- resource closure idempotence

Stress coverage:

- repeated open/execute/commit/rollback/close cycles
- prepared-statement reuse under load
- cooperative cancel on long-running remote statements
- remote protocol round-trips through the JDBC driver

## 13. Delivery Criteria

The JDBC driver is considered delivered when:

- `DriverManager.getConnection(...)` works for embedded and remote URLs
- prepared statements, callable statements, commits, rollbacks, savepoints, generated keys, and metadata are verified in tests
- the module is documented and packaged in the normal Gradle build
- unsupported JDBC behavior fails explicitly and predictably
