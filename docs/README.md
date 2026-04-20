# DaisyBase Documentation Handbook

This handbook is the main route through the DaisyBase repository.

It is organized for three kinds of readers:

- someone trying to get the system running
- someone integrating with it through JDBC, installers, or the demo app
- someone changing the code and needing to understand how the pieces fit together

## How to Use This Handbook

If you are new here, do not start with the deepest reference page. Start with orientation, then move into the specific surface you need.

### Orientation

- [Read Me First](guides/read-me-first.md)
- [Choose Your Runtime](guides/choose-your-runtime.md)
- [How DaisyBase Thinks](guides/how-daisybase-thinks.md)
- [Building an App with JDBC and ORM](guides/building-an-app-with-jdbc-and-orm.md)

### Setup

- [Quickstart](getting-started/quickstart.md)
- [Installer Guide](getting-started/installers.md)

### Architecture

- [System Overview](architecture/system-overview.md)
- [Module Map](architecture/module-map.md)
- [Storage and Recovery](architecture/storage-recovery.md)
- [Query Lifecycle](architecture/query-lifecycle.md)

### Product Reference

- [Runtime Surfaces](reference/runtime-surfaces.md)
- [SQL Surface](reference/sql-surface.md)
- [JDBC Surface](reference/jdbc-surface.md)
- [ORM Tooling](reference/orm-tooling.md)
- [Security and Distributed XA](reference/security-and-distributed-xa.md)
- [Operations Runbook](reference/operations-runbook.md)
- [Testing and Quality](reference/testing-and-quality.md)
- [Demo Business App](reference/demo-business-app.md)
- [Known Limits and Roadmap](reference/known-limits-and-roadmap.md)

### Documentation and Machine-Readable Metadata

- [50-Point Documentation Plan](50-point-documentation-plan.md)
- [MCP Description System](mcp-description-system.md)
- [ORM MCP Server](../tools/daisybase-orm-mcp/README.md)

## Documentation Standard

The rule for DaisyBase documentation is simple: be useful before being impressive.

In practice, that means:

- write in direct sentences
- state what a feature does and what it does not do
- prefer structure over flourish
- keep limits visible
- make navigation obvious

## Accessibility Notes

The documentation system aims to be easy to read and easy to navigate:

- one heading hierarchy per page
- explicit link labels
- semantic structure instead of visual-only emphasis
- readable line lengths
- no meaning conveyed by color alone
- keyboard-accessible portal with skip link and visible focus states

The human-facing docs and the machine-readable catalog are meant to describe the same system, not two different stories.

## GitHub Pages API Suite

The repository also ships a Pages-ready documentation build:

- the portal at `docs/site/index.html`
- a generated API atlas with module/package/type coverage
- themed Javadocs for each Java module

Build it locally from the repository root with:

```bash
bash ./gradlew --no-daemon githubPagesSite
```

The output is written to `build/gh-pages/`.
