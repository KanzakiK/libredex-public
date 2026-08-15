# Generate a local release keystore (never committed) and print build env vars.
param(
    [string]$KeyAlias = 'libredex',
    [string]$StorePass = '',
    [string]$KeyPass = '',
    [string]$OutDir = "$env:LOCALAPPDATA\libredex",
    [string]$DName = 'CN=LibreDeX,O=LibreDeX,C=CN'
)

$ErrorActionPreference = 'Continue'
if (-not $StorePass) {
    $chars = 48..57 + 65..90 + 97..122
    $StorePass = -join (1..24 | ForEach-Object { [char]$chars[(Get-Random -Maximum $chars.Count)] })
}
if (-not $KeyPass) { $KeyPass = $StorePass }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$keystore = Join-Path $OutDir 'libredex-release.keystore'
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -alias $KeyAlias -storepass $StorePass -keypass $KeyPass `
        -dname $DName -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
} else {
    Write-Output "Keystore already exists: $keystore (delete it to regenerate)"
}

Write-Output ''
Write-Output 'Set these env vars before building Release:'
Write-Output "  DEXANYWHERE_KEYSTORE=$keystore"
Write-Output "  DEXANYWHERE_KEYSTORE_PASSWORD=$StorePass"
Write-Output "  DEXANYWHERE_KEY_ALIAS=$KeyAlias"
Write-Output "  DEXANYWHERE_KEY_PASSWORD=$KeyPass"
Write-Output 'Use $env:NAME=value in PowerShell; keep passwords safe.'
