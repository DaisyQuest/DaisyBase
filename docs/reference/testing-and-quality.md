# Testing and Quality

## Test Layers

DaisyBase currently documents these quality layers:

- parser and binder tests
- storage and WAL tests
- engine integration tests
- JDBC integration tests
- server protocol tests
- installer tests
- demo application tests
- stress and restart tests

## Benchmarks

The benchmark layer documents micro and macro style workloads through `bench/`.

## Coverage Philosophy

Coverage gates are used selectively where the project already enforces them. The documentation system follows the same principle: validate what is shipped and be explicit about what is not yet automated.
