# Installer Release Packaging

The installer now emits release metadata directly into each installed tree:

- `release/INSTALLATION-MANIFEST.properties`
- `release/SHA256SUMS`

Use the packaging helpers in `installer/scripts/` to stage a fresh installation and archive it:

```powershell
pwsh ./installer/scripts/package-release.ps1
```

```bash
./installer/scripts/package-release.sh
```

Both scripts stage the install under `installer/build/release/staging/`, create an archive in
`installer/build/release/archives/`, and write archive checksums alongside the packaged output.

QoL and migration details:

- installs now write both `config/daisybase.properties` and `config/javadb.properties`
- branded launchers are emitted as `daisybase-server.*` and `daisybase-cli.*`
- compatibility launchers `start-server.*` and `run-cli.*` remain in place

## Demo Business Installer

The installer module also ships a dedicated `demo-business` profile for the TomEE-based enterprise demo application.

Repo-local install entrypoints:

```powershell
pwsh ./scripts/install-demo.ps1 -Gui
pwsh ./scripts/install-demo.ps1 -NonInteractive -AcceptDefaults
```

```bash
./scripts/install-demo.sh --gui
./scripts/install-demo.sh --non-interactive --accept-defaults
```

Packaged installer bundles:

```powershell
pwsh ./installer/scripts/package-demo-release.ps1
```

```bash
./installer/scripts/package-demo-release.sh
```

The demo installer bundle includes the installer runtime, the packaged demo WAR, Windows/Linux launch scripts, and
downloads Temurin JDK 21 plus Apache TomEE Plus `10.1.4` when local homes are not supplied.
