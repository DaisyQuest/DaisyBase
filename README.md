# DaisyBase

DaisyBase is a relational database system for Java 21.

It is designed for people who want one codebase that can act as:

- an embeddable database engine
- a server process with a binary wire protocol
- a JDBC-accessible database for applications and tools
- a practical platform for studying storage, recovery, SQL execution, and database ergonomics

This repository also includes:

- a lightweight ORM and schema-to-code generator
- Windows and Linux installers for GUI and headless deployment
- a TomEE demo business application
- a machine-readable MCP description layer for the system and its ORM tooling

## Why This Project Exists

DaisyBase is not trying to win by pretending to be every database at once.

The point is different: build a serious, comprehensible system with durable storage, recovery, JDBC integration, runtime tooling, and a clear path for extension. The code aims to be useful to application developers, readable to engineers, and honest about the boundaries of what is and is not implemented.

## What You Can Use Today

Today, the repository contains a working vertical slice with:

- an embedded engine API
- a server runtime
- a JDBC driver
- generated keys, sequences, routines, authentication, and bounded XA support
- paged storage and WAL-backed recovery primitives
- a demo application deployed as a TomEE WAR
- ORM tooling for straightforward entity CRUD and code generation
- documentation and MCP servers that describe the product and its tooling surfaces

## Start Here

If you are new to the repository, these are the most useful entry points:

- [Read Me First](docs/guides/read-me-first.md)
- [Choose Your Runtime](docs/guides/choose-your-runtime.md)
- [How DaisyBase Thinks](docs/guides/how-daisybase-thinks.md)
- [Documentation Handbook](docs/README.md)
- [Installer Guide](installer/README.md)
- [Demo Business App](demo-business-app/README.md)
- [JDBC Module](jdbc/README.md)
- [ORM Tooling Reference](docs/reference/orm-tooling.md)
- [MCP Description System](docs/mcp-description-system.md)

## Quick Build

```powershell
./gradlew.bat build
```

Documentation validation:

```powershell
./scripts/validate-docs.ps1
```

```bash
./scripts/validate-docs.sh
```

GitHub Pages documentation build:

```bash
bash ./gradlew --no-daemon githubPagesSite
```

The generated Pages site lands in `build/gh-pages/` and includes the portal, themed module Javadocs, and a generated API atlas for the full public Java surface.

## Repository Guide

The codebase is organized as a multi-module Gradle build:

- `common`: shared types, values, identifiers, and primitive contracts
- `sql-frontend`: parser, AST, diagnostics, and the PL/SQL reference bridge
- `catalog`: durable metadata, auth, roles, grants, routines, and sequences
- `planner`: binding, parameter inference, logical planning, and physical selection
- `execution`: runtime operators, DML, aggregates, joins, and routines
- `storage`: paged persistence, overflow values, and recovery-facing page state
- `txn`: transaction lifecycle, snapshots, savepoints, and XA branch state
- `wal`: log append, metadata, checkpoints, and restart primitives
- `index`: index metadata and maintenance
- `engine-api`: embedded engine, metadata, transport, sequences, routines, and XA integration
- `server`: binary protocol server runtime
- `cli`: shell runtime
- `jdbc`: JDBC driver and integration surface
- `orm`: annotation-based ORM, schema introspection, and source generation
- `installer`: core installer and demo installer
- `demo-business-app`: TomEE enterprise demo
- `testkit` and `bench`: quality tooling and benchmarks

## Migration Note

The product is now branded as `DaisyBase`.

Migration has been made intentionally gentle:

- `jdbc:daisybase:` is the primary JDBC scheme
- legacy `jdbc:javadb:` URLs are still accepted
- legacy `dev.javadb.jdbc` entrypoints remain available as compatibility shims
- installer output includes both `daisybase.properties` and `javadb.properties`

## Expectations

This repository is ambitious, but it is not casual about correctness. Unsupported features should fail explicitly. Durability and recovery are treated as product concerns, not decoration. The documentation should help a serious engineer get oriented quickly, not drown them in slogans.

## Contributing and Support

- [Contributing Guide](CONTRIBUTING.md)
- [Security Policy](SECURITY.md)
- [Support Guide](SUPPORT.md)
