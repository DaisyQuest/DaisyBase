# ORM Tooling

## Runtime ORM Module

The `orm` module provides:

- annotation-based entity mapping
- generated identity support
- CRUD through `DaisyBaseEntityManager`
- repository wrappers through `DaisyBaseRepository`
- simple filtered query building through `DaisyBaseQuery`

## Introspection and Code Generation

The ORM module also provides:

- schema introspection over JDBC metadata
- Java source generation for entity and repository classes
- a CLI for schema JSON, generated source JSON, and file emission

## MCP Generator Server

The `tools/daisybase-orm-mcp/` server exposes ORM generation as MCP tools.

Representative tools:

- `introspect_schema`
- `generate_entity_bundle`
- `write_entity_bundle`

## Current Limits

- composite keys are not supported
- mapping is intentionally field-based and aimed at straightforward entity classes
- the query builder is intentionally narrow and does not try to replace SQL joins or advanced query planning
