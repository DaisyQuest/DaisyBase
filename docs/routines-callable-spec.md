# JavaDB Routines, Functions, And CallableStatement Specification

## 1. Objective

Build real engine-side routine semantics before implementing JDBC `CallableStatement` so procedure and function execution is not a hollow driver shim.

The first credible routine subsystem must:

- persist routine definitions in the catalog
- execute stored procedures and stored scalar functions inside the engine
- support `CALL proc(...)` and scalar function invocation in SQL where the function contract is compatible with the current executor
- provide the engine semantics required for a future JDBC `CallableStatement`

## 2. Scope

### 2.1 In Scope

- cataloged stored routine objects
- `CREATE FUNCTION` and `CREATE PROCEDURE` DDL for a constrained initial language
- `CALL proc(...)` execution
- scalar function invocation from expressions for stored SQL functions
- IN parameters for the initial slice
- zero or one tabular result set from procedures
- routine execution through embedded and remote modes

### 2.2 Out Of Scope For The Initial Slice

- PL/SQL procedural completeness
- exception blocks, loops, cursors, and package semantics
- overloaded routines
- INOUT/OUT parameters unless the engine path is already solid enough to do them cleanly
- definer/invoker security

## 3. Language Strategy

Use a staged routine language strategy compatible with the existing reference PL/SQL bridge:

### Stage 1

- SQL-language functions and procedures only
- routine body is a validated SQL statement or statement batch that uses named parameters
- procedures may execute DML and optionally end in a single result-producing `SELECT`
- functions must return exactly one scalar value derived from a single-row/single-column `SELECT` or an equivalent expression contract

### Stage 2 Path

- expand to reference-parser-backed PL/SQL blocks once the catalog, invocation, and transaction semantics are proven

This avoids coupling early routine execution to the entire PL/SQL surface.

## 4. SQL Surface

### 4.1 Routine DDL

Support:

- `CREATE FUNCTION fn_name(arg_name type, ...) RETURNS type AS <body>`
- `CREATE PROCEDURE proc_name(arg_name type, ...) AS <body>`

The exact body delimiter syntax may be adapted to the current parser as long as it is documented and deterministic.

### 4.2 Invocation

Support:

- `CALL proc_name(...)`
- `SELECT fn_name(col) FROM table`
- `SELECT fn_name(?)` through JDBC prepared statements once function execution is bound and planned

Unsupported invocation forms must fail explicitly.

## 5. Catalog Model

Add durable routine definitions to the catalog.

### 5.1 New Definition Types

- `RoutineDefinition`
  - object id
  - qualified name
  - kind: function / procedure
  - parameter list with names, types, and mode
  - return type for functions
  - language kind: `SQL`
  - normalized body text
  - dependency metadata for referenced objects if available

### 5.2 Snapshot Visibility

- routine definitions participate in immutable catalog snapshots
- transactional DDL semantics match tables and indexes
- dropping or replacing routines is out of scope unless implemented cleanly with versioned metadata

## 6. SQL Frontend

### 6.1 Parser

Add AST support for:

- `CreateFunctionStatement`
- `CreateProcedureStatement`
- `CallStatement`

The parser must preserve source spans and fail explicitly on unsupported routine options.

### 6.2 Binder

- bind routine references from `CALL` and scalar expressions
- validate argument count and argument type compatibility
- reject procedures used in scalar expression positions
- reject functions used in statement-only positions

## 7. Execution Model

### 7.1 Procedure Execution

Procedures execute inside the current session and transaction context.

Rules:

- procedure body executes with the caller transaction
- procedure may run multiple statements
- if the final statement yields a row set, that row set becomes the procedure result set
- statement failure rolls back using existing transaction/savepoint semantics

### 7.2 Function Execution

Functions must be side-effect constrained in the initial slice.

Rules:

- functions may not run mutating DML
- function result must be scalar
- function evaluation from queries must remain deterministic within a statement snapshot
- function invocation cost must be visible to the planner even if the first implementation uses a simple scalar callback path

## 8. Planner Integration

### 8.1 Scalar Functions

Add a bound expression form for stored scalar function calls.

The planner may initially lower them to an execution-time callback expression, but it must keep type information and nullability explicit.

### 8.2 `CALL`

Treat `CALL` as a statement plan node with:

- routine metadata
- bound arguments
- result-set contract

## 9. Engine API And Protocol

The engine API must expose routine execution through ordinary session execution, not a JDBC-only side channel.

If needed, extend `StatementResult` with routine metadata only when it materially improves downstream JDBC behavior.

Remote protocol does not require a new message family if `CALL` runs through normal SQL execution.

## 10. JDBC Path

### 10.1 Before `CallableStatement`

The engine must first support:

- `CALL` execution through regular statements
- stable metadata for routine existence and parameter lists
- predictable result-set behavior for procedures

### 10.2 `CallableStatement` Preconditions

Do not implement `CallableStatement` until at least the following are true:

- procedures execute in the engine
- functions execute in the engine
- parameter metadata is available
- result-set behavior is deterministic for procedures

Once those are true, the JDBC layer can add `prepareCall(...)` on top of real engine semantics.

## 11. PL/SQL Compatibility Path

The existing reference parser should be used to support richer PL/SQL bodies only after the SQL-routine core is working.

Requirements for the future path:

- routine catalog object remains the same
- language kind can expand from `SQL` to `PLSQL`
- unsupported procedural constructs must fail at bind time, not parse-only acceptance

## 12. Testing Requirements

### 12.1 Unit Tests

- parser coverage for create function/procedure and call
- catalog routine serialization round-trip
- binder validation for routine kind, arity, and type errors

### 12.2 Integration Tests

- create and call a stored procedure that performs DML
- create and call a stored procedure that returns a single result set
- create and use a stored scalar function in a select list and predicate
- verify transaction behavior when a procedure fails mid-execution
- verify embedded and remote parity

### 12.3 JDBC Readiness Tests

- `Statement.execute("CALL ...")` returns correct update/result behavior
- metadata probes for routines and parameters return stable structures once implemented
- future `CallableStatement` tests must be blocked on engine completeness, not skipped silently

## 13. Acceptance Criteria

This slice is done only when:

- stored procedures and functions are engine objects, not JDBC shims
- `CALL` works through normal SQL execution
- scalar stored functions can participate in supported query expressions
- routine execution respects transaction semantics
- verification passes:
  - `./gradlew.bat :sql-frontend:test :catalog:test :planner:test :execution:test :engine-api:test`
  - `./gradlew.bat build`

## 14. Recommended Ownership

Primary modules:

- `sql-frontend`
- `catalog`
- `planner`
- `execution`
- `engine-api`
- eventually `jdbc`

Tests to add or expand:

- parser routine tests
- catalog routine tests
- engine integration tests for `CALL` and stored functions
- remote protocol parity tests
