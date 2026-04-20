# Building an App with JDBC and ORM

## When to Use Which Layer

Start with JDBC if you need exact SQL control.

Add the DaisyBase ORM when your application has:

- entity-shaped tables
- single primary keys
- generated identity values
- a need for CRUD, simple filters, and metadata-driven code generation

## Typical Flow

1. Use JDBC or installers to create the database.
2. Build schema with SQL.
3. Use the ORM introspector and generator to emit starter entity and repository classes.
4. Use `DaisyBaseEntityManager` or `DaisyBaseRepository` for CRUD.
5. Drop to JDBC for complex SQL when the ORM is no longer the right abstraction.

## Why This Split Is Intentional

A lightweight ORM is useful when it does not pretend to replace SQL. DaisyBase keeps the ORM narrow and predictable so it complements the driver instead of fighting it.
