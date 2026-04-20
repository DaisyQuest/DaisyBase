# Runtime Surfaces

## Embedded Engine

Use the embedded engine when the caller and database share a JVM. This is the narrowest-latency integration path and the basis for many tests and benchmarks.

## Server Runtime

Use the server runtime when sessions connect over the DaisyBase binary protocol. The runtime includes handshake, metadata, prepared description, transaction control, and authentication checks.

## CLI

The CLI is the interactive operator and developer surface for statement execution, explain output, smoke validation, and high-throughput file-driven imports. Use `cli load --file <rows.csv> --sql "INSERT INTO target VALUES (?, ?)"` when you want the shell to stream delimited rows through the engine's prepared execution path with configurable delimiters, batch commits, null tokens, and error thresholds.

## JDBC

The JDBC driver supports embedded and remote URLs, prepared and callable statements, generated keys, metadata, updatable result sets for bounded query shapes, and bounded XA integration.

## Demo Business App

The demo application is a TomEE-hosted backend plus browser frontend that exercises DaisyBase through the shipped JDBC driver.
