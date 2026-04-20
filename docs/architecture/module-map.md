# Module Map

## Engine Core

- `common`: shared identifiers, values, data types, and primitives
- `sql-frontend`: lexer, parser, AST, reference PL/SQL bridge, diagnostics
- `catalog`: durable metadata, object state, users, roles, grants, routines, sequences
- `planner`: binder, semantic analysis, type inference, access-path selection, parameter description
- `execution`: runtime operators and expression evaluation
- `storage`: paged heap persistence, overflow storage, page images, buffer-like cache behavior
- `txn`: transaction state, snapshots, savepoints, XA branch state
- `wal`: WAL append, metadata, checkpoint and recovery primitives
- `index`: index metadata and maintenance responsibilities

## Runtime and Integration

- `engine-api`: embedded runtime, metadata APIs, protocol contracts, routines, sequence state
- `server`: protocol listener and session serving
- `cli`: interactive shell
- `jdbc`: driver, metadata, callable support, updatable result sets, XA integration
- `installer`: core and demo installer entrypoints and packaging helpers
- `demo-business-app`: TomEE enterprise sample using DaisyBase over JDBC

## Quality and Performance

- `testkit`: reusable test helpers and engine harnesses
- `bench`: benchmark harnesses and JMH entrypoints

## Ownership Principle

Each module is responsible for a narrow contract. Documentation, code, and the MCP description catalog all treat these module boundaries as the stable map of the system.
