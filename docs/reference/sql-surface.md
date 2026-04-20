# SQL Surface

## Current Areas

DaisyBase documents the currently shipped SQL surface instead of promising full standard coverage.

### DDL

Current documented DDL coverage includes:

- schemas
- tables
- indexes
- sequences
- functions and procedures
- identity columns

### DML and Query

Current documented DML and query areas include:

- `INSERT`, `UPDATE`, `DELETE`
- `SELECT`
- `EXPLAIN`
- `CALL`
- aggregates
- joins
- subquery-capable reference-query paths
- generated keys

### Types

Current documented type areas include:

- scalar numeric types including `DECIMAL(p,s)` metadata preservation
- text
- date/time/timestamp
- native JDBC object families such as blob, array, struct, ref, rowid, and sqlxml on the current engine path

### PL/SQL Bridge

DaisyBase keeps a bridge to the reference PL/SQL parser for overlap areas where reference parsing is useful. The handbook treats that as an implementation aid, not as a claim of blanket PL/SQL execution compatibility.

## Limits to Treat Explicitly

- SQL breadth is still bounded and intentionally documented that way
- unsupported features should be expected to fail explicitly rather than degrade silently
