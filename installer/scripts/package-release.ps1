param(
    [string]$OutputRoot = "",
    [string]$PackageName = "",
    [string]$JavaHome,
    [string]$ReferenceParserHome,
    [ValidateSet("disabled","auto","required")]
    [string]$ReferenceParserMode = "",
    [int]$Port = 15432,
    [int]$CheckpointInterval = 8,
    [bool]$StrictDurability = $true
)

$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
}

function Get-Version {
    param([string]$RepoRoot)
    $content = Get-Content (Join-Path $RepoRoot "build.gradle.kts") -Raw
    $match = [regex]::Match($content, 'version = "([^"]+)"')
    if (-not $match.Success) {
        throw "Failed to determine project version from build.gradle.kts"
    }
    return $match.Groups[1].Value
}

function Get-Arch {
    if ($env:PROCESSOR_ARCHITECTURE -match "ARM64") {
        return "aarch64"
    }
    return "x64"
}

$repoRoot = Get-RepoRoot
$version = Get-Version -RepoRoot $repoRoot
$resolvedOutputRoot = if ($OutputRoot) {
    [System.IO.Path]::GetFullPath($OutputRoot)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot "installer\build\release"))
}
$safePackageName = if ($PackageName) {
    [System.IO.Path]::GetFileName($PackageName)
} else {
    "javadb-$version-windows-$(Get-Arch)"
}
$stageParent = Join-Path $resolvedOutputRoot "staging"
$archiveParent = Join-Path $resolvedOutputRoot "archives"
$stageDir = Join-Path $stageParent $safePackageName
$archivePath = Join-Path $archiveParent ($safePackageName + ".zip")

New-Item -ItemType Directory -Force -Path $stageParent | Out-Null
New-Item -ItemType Directory -Force -Path $archiveParent | Out-Null

if (Test-Path $stageDir) {
    Remove-Item -LiteralPath $stageDir -Recurse -Force
}
if (Test-Path $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}

$installArgs = @{
    InstallDir = $stageDir
    DatabaseHome = (Join-Path $stageDir "db-home")
    Port = $Port
    CheckpointInterval = $CheckpointInterval
    StrictDurability = $StrictDurability
    NonInteractive = $true
}
if ($JavaHome) { $installArgs["JavaHome"] = $JavaHome }
if ($ReferenceParserHome) { $installArgs["ReferenceParserHome"] = $ReferenceParserHome }
if ($ReferenceParserMode) { $installArgs["ReferenceParserMode"] = $ReferenceParserMode }

& (Join-Path $repoRoot "scripts\install.ps1") @installArgs

Push-Location $stageParent
try {
    Compress-Archive -Path $safePackageName -DestinationPath $archivePath -Force
} finally {
    Pop-Location
}

$archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $archiveParent "SHA256SUMS") -Value "$archiveHash  $($safePackageName).zip"

Write-Host "Created release archive: $archivePath"
