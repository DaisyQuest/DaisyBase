# DaisyBase ORM MCP Server

This MCP server turns DaisyBase schema metadata into ORM starter code.

## Tools

- `introspect_schema`: return schema metadata from a live DaisyBase JDBC URL
- `generate_entity_bundle`: return generated entity and repository sources
- `write_entity_bundle`: write generated sources to an output directory

## Backing Runtime

The server shells out to the `orm` module CLI so the generated code is based on the same JDBC metadata and type mapping logic that the runtime ORM uses.

## Run

```powershell
py -3 tools/daisybase-orm-mcp/server.py
```

## Test

```powershell
py -3 tools/daisybase-orm-mcp/test_server.py
```
