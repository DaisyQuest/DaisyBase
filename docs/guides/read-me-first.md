# Read Me First

DaisyBase is easiest to understand if you separate it into three questions:

1. How do I run it?
2. How do I talk to it?
3. What is already real versus still intentionally bounded?

## How to Run It

You can use DaisyBase in process, over its server runtime, through JDBC, or indirectly through the TomEE demo application.

## How to Talk to It

- SQL through the CLI
- JDBC from Java applications
- the binary protocol through the server runtime
- the new ORM module when you want mapped entities instead of handwritten JDBC for straightforward CRUD flows

## What Is Real Today

- paged storage
- WAL-backed recovery primitives
- generated keys, sequences, routines, authentication, and bounded XA
- Windows and Linux installers
- a live demo business application
- a machine-readable MCP system catalog
- a lightweight ORM and code generator for DaisyBase-backed applications

## What Is Not Pretended Into Existence

DaisyBase documentation is intentionally explicit about bounded areas. The goal is to help you use the product correctly, not to over-claim compatibility.
