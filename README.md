# DaisyBase

DaisyBase is a custom Java 21 relational database system with:

- an embeddable engine API
- a binary wire server runtime
- a JDBC driver
- a lightweight ORM and schema-to-code generator
- durable paged storage and WAL-backed recovery
- a TomEE demo business application
- Windows and Linux installers for both the core product and the demo stack

Migration note:

- the product is now branded as `DaisyBase`
- legacy `jdbc:javadb:` URLs still work as compatibility aliases
- legacy `dev.javadb.jdbc` driver and data-source entrypoints remain available as shims

## Start Here

- [Documentation Handbook](docs/README.md)
- [Read Me First](docs/guides/read-me-first.md)
- [Choose Your Runtime](docs/guides/choose-your-runtime.md)
- [How DaisyBase Thinks](docs/guides/how-daisybase-thinks.md)
- [Build an App with JDBC and ORM](docs/guides/building-an-app-with-jdbc-and-orm.md)
- [Accessible Docs Portal](docs/site/index.html)
- [50-Point Documentation Plan](docs/50-point-documentation-plan.md)
- [MCP Description System](docs/mcp-description-system.md)
- [Installer Guide](installer/README.md)
- [Demo Business App](demo-business-app/README.md)
- [JDBC Driver Spec](docs/jdbc-driver-spec.md)
- [JDBC Testing Matrix](docs/jdbc-testing-matrix.md)
- [ORM Tooling Reference](docs/reference/orm-tooling.md)
- [ORM MCP Server](tools/daisybase-orm-mcp/README.md)

## Project Structure

- `common`: shared types and value model
- `engine-api`: embedded engine, transport, metadata, sequences, routines, XA state
- `sql-frontend`: parser, AST, PL/SQL bridge, diagnostics
- `catalog`: durable metadata, auth, roles, grants, routines, sequences
- `planner`: binder, logical planning, parameter inference, physical selection
- `execution`: runtime operators, expressions, aggregates, joins, routines
- `storage`: paged heap, buffer cache, overflow values, page images
- `txn`: transaction lifecycle, snapshots, savepoints, XA branch state
- `wal`: log append, metadata, recovery primitives
- `index`: index maintenance and access metadata
- `server`: binary protocol listener
- `cli`: shell runtime
- `jdbc`: JDBC driver, metadata, XA, updatable result sets
- `orm`: annotation-based ORM, schema introspection, and code generation
- `installer`: product and demo installers
- `demo-business-app`: TomEE-backed enterprise demo
- `testkit`, `bench`: quality and benchmark support

## Build

```powershell
./gradlew.bat build
```

## Validate Documentation

```powershell
./scripts/validate-docs.ps1
```

```bash
./scripts/validate-docs.sh
```
