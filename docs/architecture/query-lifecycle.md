# Query Lifecycle

## End-to-End Flow

1. SQL text enters the native parser.
2. Supported reference-parser handoff is used where PL/SQL-adjacent or richer reference syntax is intentionally bridged.
3. The binder resolves names, types, coercions, routines, and parameter descriptions against a catalog snapshot.
4. The planner produces bound and physical forms for scans, joins, aggregates, routines, DML, and explain output.
5. The executor runs the physical plan and coordinates with storage, transactions, sequences, generated keys, and JDBC result handling.

## Prepared Statements

Prepared statements preserve placeholders, infer parameter metadata where possible, and expose that description through the engine API, remote protocol, and JDBC.

## Generated Keys

Identity columns and sequences feed generated-key propagation through execution and JDBC result handling.

## Routines

SQL-backed procedures and functions are catalog objects. `CALL` and callable JDBC execution operate through that routine path.
