# Quickstart

## Prerequisites

- Java 21
- Gradle wrapper execution enabled
- Local filesystem access for database home and WAL files

## Embedded Engine Quickstart

Use the embedded engine when the application and database live in the same JVM.

1. Build the project.
2. Create a database home directory.
3. Open the engine through the DaisyBase embedded API or JDBC embedded URL.

Representative JDBC URL:

```text
jdbc:daisybase:embedded:C:/data/daisybase-home
```

## Server Quickstart

Use the server runtime when clients connect over the DaisyBase binary protocol.

1. Build the project.
2. Start the server distribution or installer output.
3. Point the JDBC driver at the configured host and port.

Representative JDBC URL:

```text
jdbc:daisybase:remote://127.0.0.1:15432/main
```

## CLI Quickstart

Use the CLI for interactive SQL execution, smoke validation, and explain plans.

Representative flow:

```powershell
./gradlew.bat :cli:installDist
./cli/build/install/cli/bin/cli.bat
```

## Demo Business App Quickstart

The demo business application deploys to TomEE and uses DaisyBase through the shipped JDBC driver.

1. Build the demo WAR.
2. Deploy it to TomEE directly or through the demo installer.
3. Open the app root in a browser.

Default development URL:

```text
http://localhost:8080/daisybase-demo-business-0.1.0-SNAPSHOT/
```

## Recommended Reading Order

1. [Installer Guide](installers.md)
2. [System Overview](../architecture/system-overview.md)
3. [Runtime Surfaces](../reference/runtime-surfaces.md)
4. [SQL Surface](../reference/sql-surface.md)
5. [JDBC Surface](../reference/jdbc-surface.md)
