# System Overview

## Core Shape

DaisyBase is a single-node relational database system written in Java 21. It is designed around four outward-facing surfaces:

- embedded engine API
- server runtime over a binary protocol
- JDBC driver
- demo business application running on TomEE

## Major Internal Layers

1. SQL frontend: parses SQL and selected PL/SQL-adjacent input into immutable structures.
2. Catalog: stores schemas, tables, indexes, routines, sequences, users, roles, and grants.
3. Planner: binds names and types, infers prepared parameter metadata, and selects physical execution paths.
4. Execution: runs scans, filters, projections, aggregates, joins, routines, DML, and generated-key paths.
5. Storage: persists pages, rows, overflow payloads, and page images.
6. WAL and recovery: durably records page changes and restart metadata.
7. Transactions: manages visibility, savepoints, and XA branch state.
8. Runtime surfaces: embedded API, server, CLI, JDBC, installers, and demo application.

## Durability Model

DaisyBase uses a paged heap store backed by a WAL layer and page-image recovery primitives. Recovery is page-LSN aware and skips stale redo images that are older than persisted page state.

## Transaction Model

DaisyBase supports MVCC-oriented reads with transaction lifecycle support, savepoints, and bounded XA coordination. `READ COMMITTED` and `REPEATABLE READ` are the baseline isolation levels documented in the engine architecture.

## Compatibility Model

DaisyBase intentionally documents the current shipped surface rather than implying full ANSI SQL or full JDBC coverage where the implementation is bounded.
