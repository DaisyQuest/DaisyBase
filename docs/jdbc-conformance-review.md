# JDBC Conformance Review

Date: 2026-04-19

## Scope

This review covers the DaisyBase JDBC driver as implemented in:

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
- catalog-backed remote authentication and role/grant authorization are enforced at the protocol and session layers
- durable prepared-branch XA recovery works for embedded and remote sessions
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
   - the driver now provides disconnected wrappers plus native engine value handling for `BLOB`, `ARRAY`, `STRUCT`, `REF`, `ROWID`, and `SQLXML`, while preserving deterministic `TEXT` encodings for compatibility columns

7. Remote authentication was previously ignored.
   Impact:
   - `user` and `password` were accepted for compatibility but never enforced by the server
   Resolution:
   - the remote handshake now authenticates against catalog-backed users, and sessions enforce role/grant authorization on table, routine, and catalog-admin operations

8. XA remained unavailable.
   Impact:
   - coordinators expecting `XADataSource` integration could not enlist DaisyBase at all
   Resolution:
   - the driver now exposes a durable prepared-branch `XADataSource` / `XAConnection` / `XAResource` implementation with recovery scans and restart-safe two-phase commit/rollback

## Current Conformance Status

### Implemented and Verified

- `Driver`, `DataSource`
- embedded and remote `Connection`
- `XADataSource`, `XAConnection`, and durable recoverable `XAResource`
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
- native and compatibility `Blob`/`Array`/`Struct`/`Ref`/`RowId` parameter binding and result/output accessors
- catalog-backed remote authentication and authorization

### Implemented With Deliberate Limits

- result sets are disconnected cached row sets
  - simple single-table queries can be updatable
  - more complex joins, grouping, expression projections, and queries without projected primary-key coverage remain read-only
- JDBC object families support two lanes
  - native engine column types (`BLOB`, `ARRAY`, `STRUCT`, `REF`, `ROWID`, `SQLXML`) now round-trip as native engine values
  - compatibility `TEXT` columns still support deterministic wrapper encodings for legacy tests and applications
- timeout/cancel is cooperative
  - long-running execution loops check cancellation and deadlines
  - the driver does not preempt arbitrary blocking code outside those checkpoints
- authentication is catalog-backed and connection-scoped
  - the server authenticates against catalog users with hashed passwords
  - sessions enforce explicit grants, including role-derived grants
- XA remains bounded
  - one `XAResource` still maps to one DaisyBase connection/session at a time
  - durable prepare/recover/commit/rollback are implemented
  - interleaved multi-branch work on one connection and full distributed transaction manager integration remain out of scope

## Remaining Gaps

These are real JDBC conformance gaps that remain after this pass:

1. Full metadata breadth
   - current status: common framework probes are covered
   - some less common `DatabaseMetaData` result-set methods remain intentionally empty or unsupported

2. Rich SQL security breadth
   - current status: catalog users, roles, and explicit grants are enforced
   - missing work: broader SQL security syntax, ownership semantics, revocation, and fine-grained schema-level policy

3. XA breadth
   - current status: durable prepare/recover/commit/rollback works for one branch per connection
   - missing work: interleaved branches on one handle and broader transaction-manager interoperability hardening

## Release Position

For the currently supported engine surface, the JDBC layer is in good shape and is backed by automated tests across embedded and remote modes.

It is still not honest to claim complete JDBC 4.3 conformance. The remaining gaps are concentrated and explicit rather than hidden.
