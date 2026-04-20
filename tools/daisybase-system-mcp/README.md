# DaisyBase System MCP Server

This server exposes the DaisyBase documentation system as structured MCP resources and tools.

## Purpose

Use this server when a tool or agent needs a reliable, machine-readable description of the DaisyBase product rather than scraping prose manually.

## Backing Sources

- `docs/system/daisybase-system-catalog.json`
- the committed handbook pages under `docs/`

## Resources

- `daisybase://overview`
- `daisybase://modules`
- `daisybase://module/<name>`
- `daisybase://surface/<name>`
- `daisybase://doc/<slug>`
- `daisybase://limits`
- `daisybase://plan/50-point`

## Tools

- `describe_system`
- `describe_module`
- `describe_surface`
- `search_docs`
- `list_known_limits`
- `coverage_status`

## Run

```powershell
py -3 tools/daisybase-system-mcp/server.py
```

## Test

```powershell
py -3 tools/daisybase-system-mcp/test_server.py
```
