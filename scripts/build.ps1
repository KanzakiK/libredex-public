# dex-anywhere one-shot build: clean -> assembleDebug/assembleRelease -> verify APK -> optional install.
# Release requires signing env vars (see gen-keystore.ps1 and signing/README.md).
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [switch]$Install,
    [string]$Apksigner = ''
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

& .\gradlew.bat clean "assemble$Configuration"
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

$versionName = (Select-String -Path app\build.gradle -Pattern 'versionName "([^"]+)"').Matches[0].Groups[1].Value
$aapt = Join-Path (Split-Path $Apksigner) 'aapt.exe'
$badging = (& $aapt dump badging $apk.FullName) -join "`n"
if ($badging -notmatch "versionName='$versionName'") {
    throw "versionName mismatch, expected $versionName"
}
Write-Output "APK checks passed (exists, signature, versionName=$versionName)"

if ($Install) {
    adb install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
}
