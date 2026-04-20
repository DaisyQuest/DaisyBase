param(
    [string]$InstallDir,
    [string]$DatabaseHome,
    [string]$JavaHome,
    [string]$TomEEHome,
    [string]$DemoWar,
    [int]$HttpPort = 8080,
    [string]$ContextPath = "daisybase-demo-business",
    [string]$EnterpriseName = "Northwind Field Systems",
    [switch]$Gui,
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

& (Join-Path $repoRoot "gradlew.bat") ":installer:installDist" ":demo-business-app:war"

$installerBat = Join-Path $repoRoot "installer\build\install\installer\bin\installer.bat"
$resolvedDemoWar = if ($DemoWar) {
    (Resolve-Path $DemoWar).Path
} else {
    (Get-ChildItem (Join-Path $repoRoot "demo-business-app\build\libs\daisybase-demo-business*.war") | Select-Object -First 1).FullName
}

$argsList = @(
    "--profile", "demo-business",
    "--repo-root", $repoRoot,
    "--demo-war", $resolvedDemoWar,
    "--http-port", $HttpPort,
    "--context-path", $ContextPath,
    "--enterprise-name", $EnterpriseName
)
if ($resolvedJavaHome) { $argsList += @("--java-home", $resolvedJavaHome) }
if ($InstallDir) { $argsList += @("--install-dir", $InstallDir) }
if ($DatabaseHome) { $argsList += @("--database-home", $DatabaseHome) }
if ($TomEEHome) { $argsList += @("--tomee-home", $TomEEHome) }
if ($Gui) { $argsList += "--gui" }
if ($NonInteractive) { $argsList += "--non-interactive" }
if ($AcceptDefaults) { $argsList += "--accept-defaults" }

& $installerBat @argsList
