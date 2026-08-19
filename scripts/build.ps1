# dex-anywhere one-shot build: clean -> assembleDebug/assembleRelease -> verify APK -> optional install.
# Release requires signing env vars (see gen-keystore.ps1 and signing/README.md).
# Version entry: change -VersionName / -VersionCode (or the defaults below) once for a release.
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [switch]$Install,
    [string]$Apksigner = '',
    [string]$VersionName = '0.1.13',
    [int]$VersionCode = 13,
    [string]$OptionalTransportModule = '',
    [string]$OptionalTransportProvider = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($Configuration -eq 'Release') {
    $required = @('DEXANYWHERE_KEYSTORE', 'DEXANYWHERE_KEYSTORE_PASSWORD', 'DEXANYWHERE_KEY_ALIAS', 'DEXANYWHERE_KEY_PASSWORD')
    $missing = @($required | Where-Object { -not (Get-Item "env:$_" -ErrorAction SilentlyContinue) })
    if ($missing.Count -gt 0) {
        throw "Release signing requires env vars: $($missing -join ', '). Run scripts\gen-keystore.ps1 first."
    }
}

$gradleArgs = @('clean', "assemble$Configuration",
    "-PlibredexVersionName=$VersionName",
    "-PlibredexVersionCode=$VersionCode")
if ($OptionalTransportModule) {
    $gradleArgs += "-PoptionalTransportModule=$OptionalTransportModule"
}
if ($OptionalTransportProvider) {
    $gradleArgs += "-PoptionalTransportProvider=$OptionalTransportProvider"
}
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$variant = $Configuration.ToLowerInvariant()
$apk = Get-ChildItem "app\build\outputs\apk\$variant" -Filter *.apk |
    Where-Object { $_.Name -like "app-$variant*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { throw "APK not found under app\build\outputs\apk\$variant" }
Write-Output "APK: $($apk.FullName)"

if (-not $Apksigner) {
    $sdkDir = $env:ANDROID_HOME
    if (-not $sdkDir -and (Test-Path .\local.properties)) {
        $line = Get-Content .\local.properties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($line) { $sdkDir = ($line -replace '^sdk.dir=', '') -replace '\\\\', '\' }
    }
    if (-not $sdkDir) { throw 'Android SDK not found (set ANDROID_HOME or local.properties)' }
    $buildTools = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $buildTools) { throw "build-tools not found: $sdkDir" }
    $Apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
}
if (-not (Test-Path $Apksigner)) { throw "apksigner not found: $Apksigner" }

& $Apksigner verify --print-certs $apk.FullName | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed' }

$aapt = Join-Path (Split-Path $Apksigner) 'aapt.exe'
$badging = (& $aapt dump badging $apk.FullName) -join "`n"
if ($badging -notmatch "versionName='$VersionName'") {
    throw "versionName mismatch, expected $VersionName"
}
if ($badging -notmatch "versionCode='$VersionCode'") {
    throw "versionCode mismatch, expected $VersionCode"
}
Write-Output "APK checks passed (exists, signature, versionName=$VersionName, versionCode=$VersionCode)"

$outputApk = $apk.FullName
if ($Configuration -eq 'Release') {
    $distDir = Join-Path $root 'dist'
    if (-not (Test-Path -LiteralPath $distDir)) {
        New-Item -ItemType Directory -Path $distDir | Out-Null
    }
    $outputApk = Join-Path $distDir "libredex-$VersionName-$variant.apk"
    Copy-Item -LiteralPath $apk.FullName -Destination $outputApk -Force
    Write-Output "Output APK: $outputApk"
}

if ($Install) {
    adb install -r $outputApk
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    $installedBadging = (& adb shell dumpsys package com.libredex) -join "`n"
    if ($installedBadging -notmatch "versionName=$VersionName") {
        throw "installed version mismatch, expected $VersionName"
    }
    Write-Output "Installed and verified $VersionName on device"
}
