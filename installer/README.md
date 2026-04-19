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
