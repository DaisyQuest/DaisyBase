# Installer Guide

## Installer Families

DaisyBase currently ships two installer families:

- the core product installer in `installer/`
- the TomEE-based demo-business installer profile

## Windows

### Repo-local Windows installer

```powershell
pwsh ./scripts/install-demo.ps1 -Gui
pwsh ./scripts/install-demo.ps1 -NonInteractive -AcceptDefaults
```

### Packaged Windows bundle

- `install-demo-gui.bat`
- `install-demo-gui.ps1`
- `install-demo-headless.bat`
- `install-demo-headless.ps1`

## Linux

### Repo-local Linux installer

```bash
./scripts/install-demo.sh --gui
./scripts/install-demo.sh --non-interactive --accept-defaults
```

### Packaged Linux bundle

- `install-demo-gui.sh`
- `install-demo-headless.sh`

## GUI Mode

GUI mode opens a Swing wizard for:

- install directory
- database home
- optional `JAVA_HOME`
- optional `TomEE home`
- demo WAR path
- HTTP port
- context path
- enterprise display name

Blank `JAVA_HOME` downloads Temurin JDK 21. Blank TomEE home downloads Apache TomEE Plus `10.1.4`.

## Headless Mode

Headless mode accepts all inputs as arguments and is suitable for CI, smoke installs, and repeatable local environment setup.

## Installed Layout

Each demo install writes:

- `app/<context>.war`
- `config/demo-business.properties`
- `tomee/`
- `runtime/` when JDK bundling is used
- `start-demo.*`, `stop-demo.*`, `run-demo-foreground.*`
- `release/INSTALLATION-MANIFEST.properties`
- `release/SHA256SUMS`

## Port Model

The installer configures:

- HTTP port: user-selected
- TomEE shutdown port: deterministically derived from HTTP port to avoid collisions with an existing TomEE instance

## Packaging Outputs

The packaging scripts create:

- Windows ZIP archive
- Linux tar.gz archive
- archive checksums

## Current Limits

- upgrade-in-place is not yet automated
- uninstall is manual today
- the demo installer is production-shaped but still focused on the demo deployment path rather than a multi-service enterprise installer framework
