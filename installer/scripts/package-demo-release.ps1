param(
    [string]$OutputRoot = "",
    [string]$PackageName = "",
    [string]$JavaHome
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
    if ($env:PROCESSOR_ARCHITECTURE -match "ARM64") { return "aarch64" }
    return "x64"
}

$repoRoot = Get-RepoRoot
$version = Get-Version -RepoRoot $repoRoot
$resolvedOutputRoot = if ($OutputRoot) {
    [System.IO.Path]::GetFullPath($OutputRoot)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot "installer\build\demo-release"))
}
$safePackageName = if ($PackageName) {
    [System.IO.Path]::GetFileName($PackageName)
} else {
    "daisybase-demo-business-installer-$version-windows-$(Get-Arch)"
}
$stageParent = Join-Path $resolvedOutputRoot "staging"
$archiveParent = Join-Path $resolvedOutputRoot "archives"
$stageDir = Join-Path $stageParent $safePackageName
$archivePath = Join-Path $archiveParent ($safePackageName + ".zip")

New-Item -ItemType Directory -Force -Path $stageParent | Out-Null
New-Item -ItemType Directory -Force -Path $archiveParent | Out-Null
if (Test-Path $stageDir) { Remove-Item -LiteralPath $stageDir -Recurse -Force }
if (Test-Path $archivePath) { Remove-Item -LiteralPath $archivePath -Force }

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}

& (Join-Path $repoRoot "gradlew.bat") ":installer:installDist" ":demo-business-app:war"

$installerDist = Join-Path $repoRoot "installer\build\install\installer"
$demoWar = (Get-ChildItem (Join-Path $repoRoot "demo-business-app\build\libs\daisybase-demo-business*.war") | Select-Object -First 1).FullName

New-Item -ItemType Directory -Force -Path (Join-Path $stageDir "installer") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stageDir "payload") | Out-Null
Copy-Item -LiteralPath $installerDist -Destination (Join-Path $stageDir "installer") -Recurse -Force
Copy-Item -LiteralPath $demoWar -Destination (Join-Path $stageDir "payload\daisybase-demo-business.war") -Force

$headlessPs1 = @'
param(
    [string]$InstallDir,
    [string]$DatabaseHome,
    [string]$JavaHome,
    [string]$TomEEHome,
    [int]$HttpPort = 8080,
    [string]$ContextPath = "daisybase-demo-business",
    [string]$EnterpriseName = "Northwind Field Systems",
    [switch]$NonInteractive,
    [switch]$AcceptDefaults
)

$ErrorActionPreference = "Stop"
$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}
$installerBat = Join-Path $bundleRoot "installer\installer\bin\installer.bat"
$argsList = @(
  "--profile", "demo-business",
  "--demo-war", (Join-Path $bundleRoot "payload\daisybase-demo-business.war"),
  "--http-port", $HttpPort,
  "--context-path", $ContextPath,
  "--enterprise-name", $EnterpriseName
)
if ($InstallDir) { $argsList += @("--install-dir", $InstallDir) }
if ($DatabaseHome) { $argsList += @("--database-home", $DatabaseHome) }
if ($JavaHome) { $argsList += @("--java-home", $JavaHome) }
if ($TomEEHome) { $argsList += @("--tomee-home", $TomEEHome) }
if ($NonInteractive) { $argsList += "--non-interactive" }
if ($AcceptDefaults) { $argsList += "--accept-defaults" }
& $installerBat @argsList
'@
$guiPs1 = @'
param(
    [string]$InstallDir,
    [string]$DatabaseHome,
    [string]$JavaHome,
    [string]$TomEEHome,
    [int]$HttpPort = 8080,
    [string]$ContextPath = "daisybase-demo-business",
    [string]$EnterpriseName = "Northwind Field Systems"
)

$ErrorActionPreference = "Stop"
$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}
$installerBat = Join-Path $bundleRoot "installer\installer\bin\installer.bat"
$argsList = @(
  "--profile", "demo-business",
  "--gui",
  "--demo-war", (Join-Path $bundleRoot "payload\daisybase-demo-business.war"),
  "--http-port", $HttpPort,
  "--context-path", $ContextPath,
  "--enterprise-name", $EnterpriseName
)
if ($InstallDir) { $argsList += @("--install-dir", $InstallDir) }
if ($DatabaseHome) { $argsList += @("--database-home", $DatabaseHome) }
if ($JavaHome) { $argsList += @("--java-home", $JavaHome) }
if ($TomEEHome) { $argsList += @("--tomee-home", $TomEEHome) }
& $installerBat @argsList
'@
$headlessBat = "@echo off`r`npowershell -ExecutionPolicy Bypass -File ""%~dp0install-demo-headless.ps1"" %*`r`n"
$guiBat = "@echo off`r`npowershell -ExecutionPolicy Bypass -File ""%~dp0install-demo-gui.ps1"" %*`r`n"

Set-Content -LiteralPath (Join-Path $stageDir "install-demo-headless.ps1") -Value $headlessPs1
Set-Content -LiteralPath (Join-Path $stageDir "install-demo-gui.ps1") -Value $guiPs1
Set-Content -LiteralPath (Join-Path $stageDir "install-demo-headless.bat") -Value $headlessBat
Set-Content -LiteralPath (Join-Path $stageDir "install-demo-gui.bat") -Value $guiBat
Set-Content -LiteralPath (Join-Path $stageDir "README.txt") -Value @"
DaisyBase Demo Business installer bundle

GUI:
  install-demo-gui.bat
  install-demo-gui.ps1

Headless:
  install-demo-headless.bat --non-interactive --accept-defaults
  install-demo-headless.ps1 --non-interactive --accept-defaults

The installer will download Temurin JDK 21 and Apache TomEE Plus $version when JAVA_HOME and TomEEHome are not provided.
"@

Push-Location $stageParent
try {
    Compress-Archive -Path $safePackageName -DestinationPath $archivePath -Force
} finally {
    Pop-Location
}

$archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $archiveParent "SHA256SUMS") -Value "$archiveHash  $($safePackageName).zip"
Write-Host "Created demo installer archive: $archivePath"
