<#
.SYNOPSIS
    Generate Android Release signing keystore (RSA 2048 / PKCS12).

.PARAMETER Force
    Backup existing keystore and regenerate (breaks in-place upgrades).

.EXAMPLE
    .\generate-keystore.ps1
    .\generate-keystore.ps1 -Force
#>

[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$BuildDir = $PSScriptRoot
$KeyDir = Join-Path $BuildDir 'key'
$KeystorePath = Join-Path $KeyDir 'release.keystore'
$PropsPath = Join-Path $KeyDir 'keystore.properties'
$Alias = 'txjs-release'
$ValidityDays = 10000
$DName = 'CN=TXJS, OU=TXZKKFZ, O=TXZK, C=CN'

function Write-Info([string]$Msg) { Write-Host "[INFO] $Msg" -ForegroundColor Cyan }
function Write-Ok([string]$Msg) { Write-Host "[DONE] $Msg" -ForegroundColor Green }
function Write-Warn([string]$Msg) { Write-Host "[WARN] $Msg" -ForegroundColor Yellow }
function Write-Err([string]$Msg) { Write-Host "[ERROR] $Msg" -ForegroundColor Red }

function Find-KeytoolPath {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $homes = @(
        $env:JAVA_HOME,
        'C:\Program Files\Java\jdk-21.0.11',
        'C:\Program Files\Android\Android Studio\jbr'
    ) | Where-Object { $_ -and (Test-Path $_) }

    foreach ($homeDir in $homes) {
        $candidate = Join-Path $homeDir 'bin\keytool.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function New-RandomPassword([int]$Length = 32) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    -join ((1..$Length) | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

function Read-StorePassword([string]$PropertiesFile) {
    if (-not (Test-Path $PropertiesFile)) { return $null }
    foreach ($line in Get-Content $PropertiesFile) {
        if ($line -like 'storePassword=*') {
            return $line.Substring('storePassword='.Length)
        }
    }
    return $null
}

function Show-CertificateInfo {
    param(
        [string]$KeytoolPath,
        [string]$Keystore,
        [string]$StorePass,
        [string]$KeyAlias
    )

    Write-Info 'Certificate:'
    $output = & $KeytoolPath -list -v -keystore $Keystore -alias $KeyAlias -storepass $StorePass 2>&1
    $patterns = @(
        'Alias name:',
        'Creation date:',
        'Owner:',
        'Valid from:',
        'until:',
        'Signature algorithm:',
        'Subject Public Key Algorithm:',
        'SHA1:',
        'SHA256:'
    )
    foreach ($line in $output) {
        $text = [string]$line
        foreach ($pattern in $patterns) {
            if ($text -like "*$pattern*") {
                Write-Host "  $($text.Trim())"
                break
            }
        }
    }
}

$keytoolPath = Find-KeytoolPath
if (-not $keytoolPath) {
    Write-Err 'keytool not found. Install JDK 21 or set JAVA_HOME.'
    exit 1
}

if (-not (Test-Path $KeyDir)) {
    New-Item -ItemType Directory -Path $KeyDir -Force | Out-Null
}

if ((Test-Path $KeystorePath) -and -not $Force) {
    $storePass = Read-StorePassword $PropsPath
    if (-not $storePass) {
        Write-Err "Keystore exists but storePassword missing in $PropsPath"
        Write-Info 'Re-run with -Force to regenerate.'
        exit 1
    }
    Write-Ok 'Release keystore already exists.'
    Show-CertificateInfo -KeytoolPath $keytoolPath -Keystore $KeystorePath -StorePass $storePass -KeyAlias $Alias
    Write-Info "Config: $PropsPath"
    exit 0
}

if ($Force -and (Test-Path $KeystorePath)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    Copy-Item -LiteralPath $KeystorePath -Destination "$KeystorePath.bak-$stamp" -Force
    Write-Warn "Backed up keystore to release.keystore.bak-$stamp"
    if (Test-Path $PropsPath) {
        Copy-Item -LiteralPath $PropsPath -Destination "$PropsPath.bak-$stamp" -Force
        Write-Warn "Backed up config to keystore.properties.bak-$stamp"
    }
}

$storePassword = New-RandomPassword
$keyPassword = $storePassword

Write-Info "Generating keystore: $KeystorePath"
Write-Info "Alias=$Alias RSA-2048 validity=$ValidityDays days"

& $keytoolPath -genkeypair -v `
    -keystore $KeystorePath `
    -alias $Alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity $ValidityDays `
    -storetype PKCS12 `
    -storepass $storePassword `
    -keypass $keyPassword `
    -dname $DName | Out-Null

@(
    'storeFile=build/key/release.keystore'
    "storePassword=$storePassword"
    "keyAlias=$Alias"
    "keyPassword=$keyPassword"
) | Set-Content -LiteralPath $PropsPath -Encoding UTF8

Write-Ok 'Release keystore created.'
Show-CertificateInfo -KeytoolPath $keytoolPath -Keystore $KeystorePath -StorePass $storePassword -KeyAlias $Alias
Write-Warn 'Keep build/key/keystore.properties and release.keystore safe. Loss prevents signed updates.'
Write-Info "Gradle reads: $PropsPath"
