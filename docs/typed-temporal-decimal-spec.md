# DaisyBase Typed Decimal And Temporal Specification

## 1. Objective

Introduce real engine-native `DECIMAL`, `DATE`, `TIME`, and `TIMESTAMP` support so the planner, executor, storage layer, catalog, SQL frontend, and JDBC driver operate on typed values instead of tunneling them through `TEXT`.

The implementation must preserve compatibility with the current engine architecture and avoid a rewrite of the existing numeric and text paths.

## 2. Scope

### 2.1 In Scope

- new core data types in `common`
- typed literals, coercion rules, comparison semantics, and null behavior
- catalog persistence for typed columns
- storage encoding/decoding for typed values
- expression evaluation, predicate comparison, ordering, grouping, and aggregates over new types where semantically valid
- JDBC parameter and metadata mappings for new types

### 2.2 Out Of Scope For This Slice

- timezone-aware SQL types (`TIMESTAMP WITH TIME ZONE`, `TIME WITH TIME ZONE`)
- interval types
- arbitrary precision math functions beyond basic comparison and persistence
- locale-sensitive formatting/parsing

## 3. Type Model

Add the following `Common.DataType` members:

- `DECIMAL`
- `DATE`
- `TIME`
- `TIMESTAMP`

Recommended Java backing types:

- `DECIMAL` -> `java.math.BigDecimal`
- `DATE` -> `java.time.LocalDate`
- `TIME` -> `java.time.LocalTime`
- `TIMESTAMP` -> `java.time.LocalDateTime`

### 3.1 Decimal Rules

- store exact arbitrary-scale values with bounded persisted scale/precision compatible with Java `BigDecimal`
- equality is exact numeric equality after normalizing scale using `compareTo`
- ordering uses numeric comparison, not textual comparison
- basic arithmetic initially required only where already supported for numeric operators and aggregates; unsupported mixes must fail explicitly

### 3.2 Temporal Rules

- `DATE` stores calendar date only
- `TIME` stores local wall-clock time without timezone
- `TIMESTAMP` stores local date-time without timezone
- ordering is chronological within the same type
- cross-type comparison is unsupported unless explicit casts are introduced

## 4. SQL Frontend

### 4.1 Type Names

Extend type-name recognition to accept:

- `DECIMAL`
- `NUMERIC`
- `DATE`
- `TIME`
- `TIMESTAMP`

Optional precision syntax:

- parser may accept `DECIMAL(p[,s])` but engine may initially ignore precision metadata if full enforcement is not yet implemented
- if precision syntax is accepted, it must be stored or rejected explicitly, never silently discarded without documentation

### 4.2 Literal Parsing

Add support for:

- decimal numeric literals containing `.`
- date/time/timestamp typed literals through conservative syntax only, for example:
  - `DATE '2026-04-19'`
  - `TIME '10:15:30'`
  - `TIMESTAMP '2026-04-19 10:15:30'`

If typed literals are not implemented in the first patch, explicit casts must remain unsupported and the parser must reject unsupported forms deterministically.

## 5. Common Layer Changes

### 5.1 `Value`

Extend `Common.Value` so it can:

- hold new raw Java value types
- expose typed accessors
- compare same-typed values correctly
- preserve nulls and serialization compatibility

### 5.2 Coercion And Comparison

Update `Common.Values` for:

- conversion from Java inputs into new types
- literal decoding/encoding for persistence and test fixtures
- three-valued logic comparisons over decimal and temporal values
- type-safe ordering used by sort/group paths

## 6. Catalog And Metadata

- catalog snapshots must preserve the new types on column definitions
- catalog persistence and replay must round-trip typed columns without text fallback
- JDBC metadata must report:
  - `DECIMAL` -> JDBC `DECIMAL` or `NUMERIC`
  - `DATE` -> JDBC `DATE`
  - `TIME` -> JDBC `TIME`
  - `TIMESTAMP` -> JDBC `TIMESTAMP`

## 7. Storage And WAL

### 7.1 Storage Encoding

Persist new value types in the live heap/page format and any snapshot fallback formats still used in tests or recovery paths.

Requirements:

- encoding is deterministic and lossless
- decimal scale is preserved exactly
- date/time/timestamp values survive restart unchanged
- WAL/page-image recovery reproduces the same typed values

### 7.2 Backward Compatibility

Existing tables with only legacy types must still load. New typed values must not corrupt old persisted rows.

## 8. Planner And Execution

### 8.1 Binding

- resolve typed columns and literals into bound expressions with the correct result type
- reject unsupported implicit casts explicitly
- keep comparison and arithmetic semantics deterministic

### 8.2 Execution

Required support:

- projection of typed values
- filtering with `=`, `!=`, `<`, `<=`, `>`, `>=`
- ordering and grouping on same-typed values
- `MIN` / `MAX` over decimal and temporal values
- `SUM` only on numeric types including `DECIMAL`
- `AVG` on `DECIMAL` if the aggregate path can preserve scale correctly; otherwise reject with `UNSUPPORTED_FEATURE`

## 9. JDBC Requirements

### 9.1 Parameters

The JDBC layer must bind real typed values end to end instead of stringifying them before engine execution for supported typed columns.

Required supported setters:

- `setBigDecimal`
- `setDate`
- `setTime`
- `setTimestamp`
- `setObject` with `BigDecimal`, `LocalDate`, `LocalTime`, `LocalDateTime`, and matching JDBC temporal objects

### 9.2 Result Sets

Result retrieval must work through standard JDBC getters, at minimum:

- `getBigDecimal`
- `getDate`
- `getTime`
- `getTimestamp`
- `getObject`

If the current disconnected row-set path blocks exact typed retrieval, replace or extend it rather than degrading values back to `String`.

## 10. Testing Requirements

### 10.1 Unit Tests

- type parsing and coercion
- `Value` comparison and serialization for new types
- storage encode/decode round-trips
- parser tests for type names and typed literals if implemented

### 10.2 Integration Tests

- create tables with decimal/date/time/timestamp columns
- insert/select round-trips through embedded engine
- sort and filter on typed columns
- aggregate support on decimal and temporal types where allowed
- JDBC parameter binding and result retrieval for each new type
- remote JDBC parity tests

### 10.3 Stress Tests

- repeated typed inserts/updates/restarts
- boundary decimals with varying scale
- temporal ordering and grouping under restart and checkpoint cycles

## 11. Acceptance Criteria

This slice is done only when:

- typed values remain typed through parser, planner, execution, storage, recovery, and JDBC
- comparisons and ordering are no longer text-based for these types
- JDBC metadata and getters expose correct types
- verification passes:
  - `./gradlew.bat :common:test :sql-frontend:test :storage:test :engine-api:test :jdbc:test`
  - `./gradlew.bat build`

## 12. Recommended Ownership

Primary modules:

- `common`
- `sql-frontend`
- `catalog`
- `storage`
- `planner`
- `execution`
- `engine-api`
- `jdbc`

Tests to add or expand:

- common value/serialization tests
- storage typed round-trip tests
- engine integration tests
- JDBC embedded and remote typed tests
