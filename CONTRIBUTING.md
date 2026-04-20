# Contributing to DaisyBase

Thank you for taking the project seriously enough to improve it.

This repository is broad: SQL frontend work, storage and recovery, JDBC, installers, the demo application, ORM tooling, and MCP servers all live here. Good contributions are welcome. Clear contributions are better.

## Before You Start

Read these first:

- [Read Me First](docs/guides/read-me-first.md)
- [How DaisyBase Thinks](docs/guides/how-daisybase-thinks.md)
- [Documentation Handbook](docs/README.md)

If your change touches product behavior, read the relevant reference document before you edit code.

## What Makes a Good Change

A strong contribution usually has these properties:

- the scope is easy to describe in one or two sentences
- the change improves a real behavior, not just local elegance
- tests prove the intended behavior or guard the regression
- documentation changes land with behavior changes when the public contract moved
- unsupported cases fail clearly rather than being silently guessed

## Development Setup

Build the repository:

```powershell
./gradlew.bat build
```

Run the documentation validation:

```powershell
./scripts/validate-docs.ps1
```

Targeted examples:

```powershell
./gradlew.bat :jdbc:test
./gradlew.bat :engine-api:test
./gradlew.bat :installer:test
./gradlew.bat :orm:test
```

## Pull Request Expectations

Please keep pull requests disciplined.

### Include

- a short description of what changed
- why the change was necessary
- the risk or tradeoff, if any
- the tests you ran
- documentation updates when user-facing behavior changed

### Avoid

- mixing unrelated refactors into a feature or fix
- staging generated artifacts unless they are intentional release outputs
- changing broad naming or packaging without explaining migration impact
- weakening explicit failure behavior just to make a test pass

## Testing Standard

"It compiles" is not enough.

At minimum, run the most relevant test slice for the module you touched. If the change crosses module boundaries, run the broader build. If you change behavior that users will read about, update the docs in the same change.

## Documentation Standard

Write for an intelligent reader who is short on time.

That means:

- say what the feature is
- say what it is for
- say what it does not do
- prefer short sections with direct titles
- do not hide limits

## Performance and Correctness

DaisyBase is not only a programming exercise. Storage, recovery, transaction handling, JDBC semantics, and installer reliability are all product work. If a change improves one axis by weakening another, say so plainly in the pull request.

## If You Are Unsure

Open a small issue or draft pull request with a concrete question. A narrow, well-framed discussion is much easier to act on than a broad speculative rewrite.
