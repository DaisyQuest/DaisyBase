# JDBC Testing Matrix

Date: 2026-04-19

This matrix maps JDBC behavior to automated coverage in the current codebase.

## Verification Commands

Primary verification:

- `./gradlew.bat :jdbc:test :engine-api:test :server:test`
- `./gradlew.bat build`

## Matrix

| Surface | Mode | Coverage | Primary Tests |
| --- | --- | --- | --- |
| Driver registration and URL parsing | Embedded, Remote | Driver auto-loading, URL parsing, DataSource wiring | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/JavaDbPreparedSqlTest.java`, `engine-api/src/test/java/dev/javadb/engine/LaunchConfigTest.java` |
| Connection lifecycle | Embedded, Remote | open, close, `isValid`, schema/catalog limits, client info, network timeout | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| Remote authentication | Remote | handshake credential acceptance and rejection | `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `server/src/test/java/dev/javadb/server/DatabaseProtocolServerTest.java`, `engine-api/src/test/java/dev/javadb/engine/LaunchConfigTest.java` |
| Transactions and savepoints | Embedded, Remote | auto-commit, explicit begin/commit/rollback, savepoint rollback | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `engine-api/src/test/java/dev/javadb/engine/EngineIntegrationTest.java` |
| XA data source | Embedded, Remote | single-branch `XADataSource` / `XAConnection` / `XAResource` prepare, commit, rollback | `jdbc/src/test/java/dev/javadb/jdbc/JavaDbXADataSourceTest.java` |
| Plain statements | Embedded, Remote | execute/query/update, update counts, multi-statement batches, explain | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| Multiple result sets | Embedded | sequential traversal and `KEEP_CURRENT_RESULT` over disconnected result sets | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java` |
| Prepared statements | Embedded, Remote | server-side prepare/describe, execution, generated-key requests, result metadata, inferred parameter metadata | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `engine-api/src/test/java/dev/javadb/engine/EngineIntegrationTest.java`, `engine-api/src/test/java/dev/javadb/engine/PreparedSqlTemplateTest.java` |
| Prepared parameter rendering | Embedded, Remote | literal rendering for numerics, decimals, strings, URLs, dates, times, timestamps, character streams | `jdbc/src/test/java/dev/javadb/jdbc/JavaDbPreparedSqlTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| Updatable result sets | Embedded, Remote | eligible single-table primary-key queries support `CONCUR_UPDATABLE`; ineligible queries downgrade to read-only explicitly | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| Callable statements | Embedded, Remote | procedure calls, function calls, named parameter access, output retrieval | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/JavaDbCallableSqlTest.java`, `engine-api/src/test/java/dev/javadb/engine/RoutineRuntimeTest.java` |
| Callable parameter metadata | Embedded, Remote | parameter count, mode, JDBC type, precision, scale | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| JDBC object wrappers | Embedded, Remote | disconnected `createClob/createNClob/createBlob/createSQLXML/createArrayOf/createStruct`; text-backed `Clob`/`NClob`/`SQLXML`; text-encoded `Blob`/`Array`/`Struct`/`Ref`/`RowId` parameter/result/output accessors | `jdbc/src/test/java/dev/javadb/jdbc/JdbcObjectWrapperTest.java` |
| Generated keys | Embedded, Remote | identity-backed insert keys for `Statement` and `PreparedStatement` | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `engine-api/src/test/java/dev/javadb/engine/EngineIntegrationTest.java` |
| Result-set type mappings | Embedded, Remote | integer, bigint, decimal, boolean, text, date, time, timestamp getters | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `engine-api/src/test/java/dev/javadb/engine/EngineIntegrationTest.java` |
| Database metadata | Embedded, Remote | schemas, tables, columns, primary keys, indexes, procedures, functions, type info, capability flags | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java`, `engine-api/src/test/java/dev/javadb/engine/EngineIntrospectionTest.java` |
| Protocol prepare/execute/cancel | Remote | remote protocol framing, prepare/describe, prepared execution, close, cancel, metadata | `engine-api/src/test/java/dev/javadb/engine/RemoteProtocolTest.java`, `server/src/test/java/dev/javadb/server/DatabaseProtocolServerTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| Timeout and cancel | Embedded, Remote | engine execution deadlines, JDBC timeout mapping, remote cancel | `engine-api/src/test/java/dev/javadb/engine/EngineIntegrationTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |
| Exception mapping | Embedded, Remote | parse errors, constraint violations, unsupported features, storage failures | `jdbc/src/test/java/dev/javadb/jdbc/EmbeddedJdbcDriverTest.java`, `jdbc/src/test/java/dev/javadb/jdbc/RemoteJdbcDriverTest.java` |

## Coverage Notes

- Embedded and remote modes are intentionally both exercised because transport differences are a major JDBC risk area.
- Engine tests remain part of JDBC validation because several JDBC guarantees rely on engine metadata, generated keys, sequence state, and routine semantics.
- The matrix is intentionally feature-based rather than method-by-method. It is meant to show whether behavior is verified, not to imply full JDBC 4.3 conformance.

## Known Uncovered Areas

- engine-backed blob, array, struct, ref, row-id, and XML support beyond `TEXT` encodings
- broader `SQLXML` `Source`/`Result` coverage beyond stream-backed access
- durable XA recovery beyond one live branch/connection
- richer authentication and authorization behavior
