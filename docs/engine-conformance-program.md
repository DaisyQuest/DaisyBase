# DaisyBase Conformance Execution Program

## 1. Purpose

This document consolidates the three subsystem specs and the parallel lane briefs into one implementation sequence that is compatible with the current codebase.

Input specs:

- `docs/identity-sequence-spec.md`
- `docs/typed-temporal-decimal-spec.md`
- `docs/routines-callable-spec.md`

## 2. Global Sequencing Rule

Do not implement these lanes independently in arbitrary order.

The shared dependency stack is:

1. `sql-frontend`
2. `catalog`
3. `common`
4. `storage`
5. `planner`
6. `execution`
7. `engine-api`
8. `server`
9. `jdbc`

The highest-conflict files are:

- `sql-frontend/src/main/java/dev/daisybase/sql/SqlFrontend.java`
- `sql-frontend/src/main/java/dev/daisybase/sql/ReferencePlsqlParserBridge.java`
- `catalog/src/main/java/dev/daisybase/catalog/Catalog.java`
- `common/src/main/java/dev/daisybase/common/Common.java`
- `planner/src/main/java/dev/daisybase/planner/Planner.java`
- `execution/src/main/java/dev/daisybase/execution/Execution.java`
- `engine-api/src/main/java/dev/daisybase/engine/EngineApi.java`
- `engine-api/src/main/java/dev/daisybase/engine/EmbeddedDatabaseEngine.java`
- `engine-api/src/main/java/dev/daisybase/engine/RemoteProtocol.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseConnection.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseStatement.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBasePreparedStatement.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseDatabaseMetaData.java`

## 3. Recommended Phase Order

### Phase A: Frontend And Catalog Foundation

Do these together before anything else:

- native parser precedence over the reference PL/SQL bridge for:
  - `CREATE SEQUENCE`
  - `NEXT VALUE FOR`
  - `CREATE FUNCTION`
  - `CREATE PROCEDURE`
  - `CALL`
- AST support for:
  - identity column clauses
  - decimal/date/time/timestamp types and literals
  - sequence statements and expressions
  - routine statements
- catalog model extensions for:
  - sequence definitions
  - identity metadata on columns
  - routine definitions
- catalog serialization/versioning updates

Why first:

- all three lanes depend on native AST and durable catalog support
- the bridge currently intercepts some syntax that must become native
- the snapshot format must change once, deliberately, instead of through overlapping ad hoc edits

Primary files:

- `sql-frontend/src/main/java/dev/daisybase/sql/SqlFrontend.java`
- `sql-frontend/src/main/java/dev/daisybase/sql/ReferencePlsqlParserBridge.java`
- `catalog/src/main/java/dev/daisybase/catalog/Catalog.java`

### Phase B: Core Type System

After Phase A, land typed runtime values:

- extend `Common.DataType`
- extend `Common.Value` and `Common.Values`
- update parser/coercion/binder expectations for typed literals and typed columns

Primary file:

- `common/src/main/java/dev/daisybase/common/Common.java`

Why second:

- sequence and routine work can continue without typed values only if you want to defer JDBC/SQL fidelity
- typed values are the root dependency for storage, comparison, and JDBC getter/setter correctness

### Phase C: Storage And Recovery

After the catalog and type model are stable, land durable state:

- typed value persistence and page recovery
- separate durable sequence state or equivalent non-rollbackable allocator path
- restart parity for new types and generated identities

Primary files:

- `storage/src/main/java/dev/daisybase/storage/Storage.java`
- `storage/src/main/java/dev/daisybase/storage/PagedTableStorage.java`
- `storage/src/main/java/dev/daisybase/storage/HeapStorageManager.java`
- `engine-api/src/main/java/dev/daisybase/engine/SequenceStore.java` new

Why third:

- this is where restart correctness is won or lost
- sequence advancement semantics conflict with normal transaction rollback and must be isolated cleanly

### Phase D: Planner And Execution

After persistence is correct, add runtime semantics:

- identity value generation during insert planning/execution
- runtime `NEXT VALUE FOR`
- typed comparison/order/group/aggregate behavior
- routine binding and execution
- scalar function purity rules

Primary files:

- `planner/src/main/java/dev/daisybase/planner/Planner.java`
- `planner/src/main/java/dev/daisybase/planner/ReferenceQueries.java`
- `execution/src/main/java/dev/daisybase/execution/Execution.java`
- `execution/src/main/java/dev/daisybase/execution/ReferenceQueryExecution.java`

### Phase E: Engine API, Metadata, And Remote Protocol

Only after the runtime semantics are real:

- add generated-key payloads to statement results
- add routine metadata to introspection
- add typed result serialization to the remote protocol
- keep remote and embedded parity

Primary files:

- `engine-api/src/main/java/dev/daisybase/engine/EngineApi.java`
- `engine-api/src/main/java/dev/daisybase/engine/EmbeddedDatabaseEngine.java`
- `engine-api/src/main/java/dev/daisybase/engine/EngineIntrospection.java`
- `engine-api/src/main/java/dev/daisybase/engine/RemoteProtocol.java`
- `server/src/main/java/dev/daisybase/server/DatabaseProtocolServer.java`

### Phase F: JDBC Completion

JDBC is last, not first.

Only then:

- surface real generated keys
- expose typed parameters and typed getters end to end
- expose routine metadata
- support `CALL` through ordinary statements
- implement `CallableStatement` only after procedure/function semantics are real

Primary files:

- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseConnection.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseStatement.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBasePreparedStatement.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBasePreparedSql.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseResultSets.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseDatabaseMetaData.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseParameterMetaData.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/RemoteDaisyBaseTransport.java`
- `jdbc/src/main/java/dev/daisybase/jdbc/DaisyBaseCallableStatement.java` new

## 4. Cross-Lane Risk Register

### 4.1 Parser Precedence Risk

The PL/SQL bridge currently sits in front of some native parsing paths. If this is not fixed first, native support for sequences and routines will appear to fail randomly depending on bridge classification.

### 4.2 Snapshot Migration Risk

`Catalog` snapshot persistence is currently narrow and versioned informally. Sequence definitions, routine definitions, and identity metadata must be added with an explicit compatibility plan.

### 4.3 Protocol Shape Risk

`EngineApi.StatementResult` and `RemoteProtocol` currently cannot carry generated keys or richer typed payload semantics cleanly. That contract must be changed once and tested hard.

### 4.4 Sequence Durability Risk

Sequences are intentionally non-rollbackable, but the current durability path is transaction-centric. Sequence allocation must not be implemented as ordinary transactional state unless gaps and restart behavior are proven correct.

### 4.5 JDBC Illusion Risk

Do not ship driver-only facades for:

- generated keys
- typed temporal/decimal bindings
- callable statements

Each of those must rest on engine behavior that already exists.

## 5. Verification Program

### Phase A Gate

- `./gradlew.bat :sql-frontend:test :catalog:test`

### Phase B Gate

- `./gradlew.bat :common:test :sql-frontend:test :catalog:test`

### Phase C Gate

- `./gradlew.bat :storage:test :engine-api:test`

### Phase D Gate

- `./gradlew.bat :planner:test :execution:test :engine-api:test`

### Phase E Gate

- `./gradlew.bat :engine-api:test :server:test`

### Phase F Gate

- `./gradlew.bat :jdbc:test :engine-api:test :server:test`

### Full Release Gate

- `./gradlew.bat build`

## 6. Subagent Instructions Template

Each implementation worker should be given:

- one owned lane and explicit files to change
- a warning that other workers may be editing adjacent modules and they must not revert unrelated work
- the exact phase gate commands above
- a requirement to list every changed file and every added/expanded test
- a requirement to call out any spec/codebase mismatch immediately instead of papering over it

## 7. Practical Conclusion

The fastest path is not three independent feature branches. The fastest path is:

1. frontend/catalog foundation
2. core types and storage
3. execution semantics
4. API/protocol
5. JDBC completion

That order is the one least likely to force rewrite churn.
