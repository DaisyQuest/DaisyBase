# DaisyBase Documentation Handbook

This handbook is the primary entry point for the DaisyBase codebase, runtime surfaces, compatibility story, operations model, and demo application.

## Documentation Goals

- Give a new engineer a fast path from zero context to productive changes.
- Give operators a direct route to installation, configuration, startup, shutdown, and troubleshooting.
- Give integrators an honest contract for SQL, JDBC, server, security, and XA behavior.
- Keep machine-readable structure aligned with the human-facing handbook through the MCP description server.

## Navigation

### Readable Guides
- [Read Me First](guides/read-me-first.md)
- [Choose Your Runtime](guides/choose-your-runtime.md)
- [How DaisyBase Thinks](guides/how-daisybase-thinks.md)
- [Building an App with JDBC and ORM](guides/building-an-app-with-jdbc-and-orm.md)

### Getting Started
- [Quickstart](getting-started/quickstart.md)
- [Installer Guide](getting-started/installers.md)

### Architecture
- [System Overview](architecture/system-overview.md)
- [Module Map](architecture/module-map.md)
- [Storage and Recovery](architecture/storage-recovery.md)
- [Query Lifecycle](architecture/query-lifecycle.md)

### Reference
- [Runtime Surfaces](reference/runtime-surfaces.md)
- [SQL Surface](reference/sql-surface.md)
- [JDBC Surface](reference/jdbc-surface.md)
- [ORM Tooling](reference/orm-tooling.md)
- [Security and Distributed XA](reference/security-and-distributed-xa.md)
- [Operations Runbook](reference/operations-runbook.md)
- [Testing and Quality](reference/testing-and-quality.md)
- [Demo Business App](reference/demo-business-app.md)
- [Known Limits and Roadmap](reference/known-limits-and-roadmap.md)

### Governance
- [50-Point Documentation Plan](50-point-documentation-plan.md)
- [MCP Description System](mcp-description-system.md)
- [ORM MCP Server](../tools/daisybase-orm-mcp/README.md)

## Accessibility Notes

The documentation system follows these rules:

- one clear heading hierarchy per page
- semantic section titles instead of visual-only emphasis
- no meaning conveyed by color alone
- readable line lengths and explicit link labels
- keyboard-accessible docs portal with skip link and visible focus states
- machine-readable catalog that mirrors the written handbook instead of diverging from it
