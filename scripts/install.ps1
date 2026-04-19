param(
    [string]$InstallDir,
    [string]$DatabaseHome,
    [string]$JavaHome,
    [string]$ReferenceParserHome,
    [ValidateSet("disabled","auto","required")]
    [string]$ReferenceParserMode = "",
    [int]$Port = 15432,
    [int]$CheckpointInterval = 8,
    [bool]$StrictDurability = $true,
    [switch]$NonInteractive,
    [switch]$AcceptDefaults
)

$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Get-TemurinArch {
    if ($env:PROCESSOR_ARCHITECTURE -match "ARM64") { return "aarch64" }
    return "x64"
}

function Get-TemurinUrl {
    $arch = Get-TemurinArch
    return "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$arch/jdk/hotspot/normal/eclipse?project=jdk"
}

function Get-TemurinRuntimeName {
    $arch = Get-TemurinArch
    return "temurin-jdk21-windows-$arch"
}

function Test-Java21 {
    param([string]$Bin)
    try {
        $versionOutput = & $Bin -version 2>&1 | Out-String
        return $versionOutput -match 'version "2[1-9]'
    } catch {
        return $false
    }
}

function Ensure-JavaHome {
    param([string]$RequestedJavaHome, [string]$RepoRoot)
    if ($RequestedJavaHome) {
        return (Resolve-Path $RequestedJavaHome).Path
    }
    if ($env:JAVA_HOME -and (Test-Java21 (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    if (Get-Command java.exe -ErrorAction SilentlyContinue) {
        if (Test-Java21 "java.exe") {
            return ""
        }
    }

    $bootstrapRoot = Join-Path $RepoRoot ".bootstrap"
    $downloadDir = Join-Path $bootstrapRoot "downloads"
    $runtimeRoot = Join-Path $bootstrapRoot "runtime"
    $runtimeName = Get-TemurinRuntimeName
    $archivePath = Join-Path $downloadDir ($runtimeName + ".zip")
    New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
    New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
    if (-not (Test-Path $archivePath)) {
        Write-Host "Downloading Temurin JDK 21..."
        Invoke-WebRequest -Uri (Get-TemurinUrl) -OutFile $archivePath
    }
    $extractRoot = Join-Path $runtimeRoot $runtimeName
    if (-not (Test-Path $extractRoot)) {
        $tempExtract = Join-Path $runtimeRoot ("unpack-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
        Expand-Archive -Force -Path $archivePath -DestinationPath $tempExtract
        $extracted = Get-ChildItem $tempExtract | Where-Object { $_.PSIsContainer } | Select-Object -First 1
        if ($null -eq $extracted) {
            Move-Item -Path $tempExtract -Destination $extractRoot
        } else {
            Move-Item -Path $extracted.FullName -Destination $extractRoot
            Remove-Item -Recurse -Force $tempExtract
        }
    }
    return $extractRoot
}

$repoRoot = Get-RepoRoot
$resolvedJavaHome = Ensure-JavaHome -RequestedJavaHome $JavaHome -RepoRoot $repoRoot
if ($resolvedJavaHome) {
    $env:JAVA_HOME = $resolvedJavaHome
    $env:Path = "$resolvedJavaHome\bin;$env:Path"
}

& (Join-Path $repoRoot "gradlew.bat") ":server:installDist" ":cli:installDist" ":installer:installDist"

$installerBat = Join-Path $repoRoot "installer\build\install\installer\bin\installer.bat"
$argsList = @(
    "--repo-root", $repoRoot,
    "--cli-dist", (Join-Path $repoRoot "cli\build\install\cli"),
    "--server-dist", (Join-Path $repoRoot "server\build\install\server"),
    "--port", $Port,
    "--checkpoint-interval", $CheckpointInterval,
    "--strict-durability", $StrictDurability.ToString().ToLowerInvariant()
)
if ($resolvedJavaHome) { $argsList += @("--java-home", $resolvedJavaHome) }
if ($InstallDir) { $argsList += @("--install-dir", $InstallDir) }
if ($DatabaseHome) { $argsList += @("--database-home", $DatabaseHome) }
if ($ReferenceParserHome) { $argsList += @("--reference-parser-home", $ReferenceParserHome) }
if ($ReferenceParserMode) { $argsList += @("--reference-parser-mode", $ReferenceParserMode) }
if ($NonInteractive) { $argsList += "--non-interactive" }
if ($AcceptDefaults) { $argsList += "--accept-defaults" }

& $installerBat @argsList
