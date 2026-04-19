# JDBC Conformance Review

Date: 2026-04-19

## Scope

This review covers the JavaDB JDBC driver as implemented in:

- `:jdbc`
- `:engine-api`
- `:server`

The review is bounded to the engine surface that actually exists today. It does not treat unsupported engine features as JDBC bugs if the driver fails them explicitly and predictably.

## Executive Summary

The driver is now credible for a practical JDBC 4.3 subset:

- embedded and remote `Connection` flows work
- `DataSource` and `XADataSource` flows work on the supported subset
- `Statement`, `PreparedStatement`, and `CallableStatement` work on the current engine surface
- identity-backed generated keys are real
- savepoints, transaction control, timeout, and cooperative cancel work
- optional static remote authentication is enforced at the protocol handshake
- bounded single-branch XA coordination works for embedded and remote sessions
- metadata for schemas, tables, columns, indexes, procedures, and functions is implemented
- non-callable prepared parameter metadata is now server-described and type-aware
- scroll-insensitive updatable result sets work for single-table primary-key-backed queries
- callable parameter metadata is now declared and type-aware instead of synthetic
- capability metadata now matches actual behavior for named callable parameters and multiple result sets
- text-backed `Clob`/`NClob`/`SQLXML` accessors work over `TEXT` values
- text-encoded `Blob`/`Array`/`Struct`/`Ref`/`RowId` accessors work over `TEXT` values
- `createBlob`, `createArrayOf`, and `createStruct` now return disconnected client-side JDBC wrappers

## Findings Reviewed In This Pass

### Resolved

1. Callable `ParameterMetaData` was previously generic and count-only.
   Impact:
   - framework code inspecting procedure/function parameters could not see declared JDBC type, mode, precision, or scale
   Resolution:
   - callable parameter metadata now loads from routine metadata and exposes mode, JDBC type, type name, precision, scale, signedness, and class name

2. `DatabaseMetaData` capability flags understated actual driver behavior.
   Impact:
   - metadata advertised `supportsNamedParameters=false` and `supportsMultipleOpenResults=false` even though callable parameter-by-name access and disconnected multi-result traversal already worked
   Resolution:
   - metadata flags now match actual driver behavior

3. Function lists in `DatabaseMetaData` were stale.
   Impact:
   - framework probes could see unsupported time/date claims and miss supported scalar functions
   Resolution:
   - numeric, string, and time/date function metadata now reflects the current engine function set

4. Non-callable `PreparedStatement.getParameterMetaData()` was generic.
   Impact:
   - frameworks inspecting ordinary prepared statements could not see inferred JDBC types, precision, scale, or nullability
   Resolution:
   - prepare/describe now preserves placeholder-aware ASTs through the engine and exposes inferred parameter metadata to JDBC

5. Result sets were disconnected but read-only.
   Impact:
   - JDBC clients expecting `CONCUR_UPDATABLE` on simple key-backed queries had to drop to manual SQL
   Resolution:
   - the driver now upgrades eligible single-table primary-key queries to updatable cached row sets and downgrades unsupported queries to read-only explicitly

6. JDBC object-wrapper coverage stopped at text LOBs.
   Impact:
   - `Blob`, `Array`, `Struct`, `Ref`, and `RowId` factories or accessors could not round-trip through supported `TEXT` columns and callable outputs
   Resolution:
   - the driver now provides disconnected wrappers and text-encoded parameter/result/output handling for those JDBC object families

7. Remote authentication was previously ignored.
   Impact:
   - `user` and `password` were accepted for compatibility but never enforced by the server
   Resolution:
   - the remote handshake now carries credentials and the server can require static configured credentials

8. XA remained unavailable.
   Impact:
   - coordinators expecting `XADataSource` integration could not enlist JavaDB at all
   Resolution:
   - the driver now exposes a bounded single-branch `XADataSource` / `XAConnection` / `XAResource` implementation over one JavaDB connection

## Current Conformance Status

### Implemented and Verified

- `Driver`, `DataSource`
- embedded and remote `Connection`
- `XADataSource`, `XAConnection`, and bounded `XAResource`
- `Statement`
- `PreparedStatement`
- `CallableStatement` for SQL-backed procedures/functions
- generated keys for identity-backed inserts
- savepoints and explicit transactions
- read-only and eligible updatable scroll-insensitive result sets
- disconnected multi-result traversal
- cooperative `Statement.cancel()`
- `Statement.setQueryTimeout(...)`
- core `DatabaseMetaData`
- `CallableStatement` named parameter access
- callable `ParameterMetaData`
- non-callable prepared `ParameterMetaData`
- `Connection.createClob/createNClob/createBlob/createSQLXML/createArrayOf/createStruct`
- text-backed `Clob`/`NClob`/`SQLXML` parameter binding and result/output accessors
- text-encoded `Blob`/`Array`/`Struct`/`Ref`/`RowId` parameter binding and result/output accessors
- optional static remote authentication

### Implemented With Deliberate Limits

- result sets are disconnected cached row sets
  - simple single-table queries can be updatable
  - more complex joins, grouping, expression projections, and queries without projected primary-key coverage remain read-only
- `Clob`, `NClob`, `SQLXML`, `Blob`, `Array`, `Struct`, `Ref`, and `RowId` are wrappers over `TEXT`
  - the engine still has no separate large-object, collection, reference, or XML storage types
  - binary/blob, array, struct, ref, and row-id round trips are implemented by deterministic text encoding rather than native on-disk types
- timeout/cancel is cooperative
  - long-running execution loops check cancellation and deadlines
  - the driver does not preempt arbitrary blocking code outside those checkpoints
- authentication is static and connection-scoped
  - the server can require one configured user/password pair
  - there is no user catalog, password hashing, or authorization model yet
- XA is bounded
  - one `XAResource` maps to one JavaDB connection/session
  - prepare/commit/rollback are supported for one branch at a time
  - recovery scans and crash-resilient distributed transaction logs are not implemented

## Remaining Gaps

These are real JDBC conformance gaps that remain after this pass:

1. Engine-native JDBC object types
   - current status: JDBC object families round-trip through `TEXT`
   - missing work: true engine and protocol support for native binary/blob, array, struct, ref, row-id, and XML storage

2. Durable distributed XA recovery
   - current status: bounded single-branch XA works through one live connection
   - missing work: prepared-transaction persistence, recovery scans, interleaved branches, and crash-recoverable distributed transaction logs

3. Rich authentication and authorization
   - current status: optional static remote handshake authentication
   - missing work: catalog-backed users, password hashing, roles, grants, and authorization checks

4. Full metadata breadth
   - current status: common framework probes are covered
   - some less common `DatabaseMetaData` result-set methods remain intentionally empty or unsupported

## Release Position

For the currently supported engine surface, the JDBC layer is in good shape and is backed by automated tests across embedded and remote modes.

It is still not honest to claim complete JDBC 4.3 conformance. The remaining gaps are concentrated and explicit rather than hidden.
