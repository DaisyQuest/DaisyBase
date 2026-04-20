# MCP Description System

## Purpose

DaisyBase now includes a machine-readable description system so human documentation and MCP-facing structured metadata stay aligned.

## Components

- `docs/system/daisybase-system-catalog.json`: canonical machine-readable catalog of the system
- `tools/daisybase-system-mcp/server.py`: MCP server exposing resources and tools over stdio
- `tools/daisybase-system-mcp/test_server.py`: unit tests for catalog loading, search, resources, and tool behavior
- `tools/daisybase-orm-mcp/server.py`: MCP server exposing schema introspection and ORM code generation over stdio
- `tools/daisybase-orm-mcp/test_server.py`: unit tests for ORM MCP tool behavior

## What It Describes

The MCP description layer covers the complete documented system map:

- product overview
- module inventory
- runtime surfaces
- SQL and JDBC surfaces
- installers
- demo business app
- ORM tooling
- security and XA
- testing and operations
- known limits
- the 50-point documentation coverage plan

## Resources

Representative resources include:

- `daisybase://overview`
- `daisybase://modules`
- `daisybase://module/<name>`
- `daisybase://surface/<name>`
- `daisybase://doc/<slug>`
- `daisybase://limits`
- `daisybase://plan/50-point`

## Tools

Representative tools include:

- `describe_system`
- `describe_module`
- `describe_surface`
- `search_docs`
- `list_known_limits`
- `coverage_status`

The ORM MCP server adds:

- `introspect_schema`
- `generate_entity_bundle`
- `write_entity_bundle`

## Why This Matters

These servers are not separate speculative knowledge bases. They read the committed DaisyBase catalog and the live ORM generator surfaces so the machine-readable layer stays tied to the same source of truth as the handbook.
