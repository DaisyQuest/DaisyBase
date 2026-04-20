# Operations Runbook

## Important Directories

- database home
- catalog snapshot directory
- data page directory
- WAL directory
- installer output directory
- demo install directory

## Startup

### Core runtime

- start embedded by opening the engine in process
- start server by launching the server distribution or installed wrapper
- start CLI through the CLI distribution

### Demo business app

- start TomEE through `start-demo.bat` or `start-demo.sh`
- stop TomEE through `stop-demo.bat` or `stop-demo.sh`
- use `run-demo-foreground.*` during local debugging

## Backup and Recovery Guidance

- keep database home, catalog, WAL, and manifest artifacts together for consistent operational reasoning
- treat installer manifests and checksums as installation provenance, not as database backup substitutes
- use WAL and storage recovery expectations when validating restart behavior after failure

## Troubleshooting Checklist

1. confirm the configured port is reachable
2. confirm the selected context path or database URL is correct
3. inspect TomEE or server logs
4. inspect catalog and WAL directories
5. verify installer manifest and generated config
6. retry with foreground launchers for direct console output
