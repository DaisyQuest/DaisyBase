# How DaisyBase Thinks

DaisyBase is structured around explicit contracts.

## The Practical Rules

- parse first, then bind, then plan, then execute
- keep catalog state durable and explicit
- treat storage and recovery as first-class architecture, not later polish
- prefer small, inspectable modules over hidden cross-layer shortcuts
- fail clearly when a feature is unsupported

## Why This Matters

This design gives you predictable failure modes. It also makes the JDBC driver, installers, demo application, ORM layer, and MCP servers easier to keep honest because they can lean on stable subsystem boundaries instead of reverse-engineering side effects.
