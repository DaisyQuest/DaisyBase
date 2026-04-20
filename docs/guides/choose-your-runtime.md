# Choose Your Runtime

## Embedded Engine

Choose embedded mode when your application owns the process and wants the fewest moving parts.

Use it for:

- tests
- local tools
- single-process applications
- direct ORM or JDBC usage

## Server Runtime

Choose the server when multiple clients or remote sessions need the same database process.

Use it for:

- service deployments
- remote JDBC clients
- centralized operational control

## Demo Business App

Choose the demo stack when you want to see DaisyBase inside a TomEE business application with both backend and frontend already wired.

## ORM Versus Raw JDBC

Use the ORM module when:

- your workload is straightforward entity CRUD
- your table shape is single-key and annotation-friendly
- you want metadata-driven code generation from the schema

Use raw JDBC when:

- you need custom SQL
- you are working with wider query shapes, joins, or procedural flows
- you want exact statement control
