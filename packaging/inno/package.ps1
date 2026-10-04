<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(\.\d+)?$')]
    [string]$Version,
    [string]$AppSource,
    [string]$GameRoot,
    [string]$IsccPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'payload.ps1')

if (-not $AppSource) {
    $AppSource = Join-Path $repoRoot 'rwpp-desktop/build/compose/binaries/main-release/app/RWJS'
}
$AppSource = [IO.Path]::GetFullPath($AppSource)
foreach ($required in @('RWJS.exe', 'app/RWJS.cfg', 'runtime/bin/server/jvm.dll', 'runtime/lib/modules')) {
    if (-not (Test-Path -LiteralPath (Join-Path $AppSource $required) -PathType Leaf)) {
        throw "Missing desktop distribution file: $required. Run :rwpp-desktop:createReleaseDistributable first."
    }
}

if (-not $IsccPath) { $IsccPath = $env:INNO_SETUP_COMPILER }
if ($IsccPath) {
    if (-not (Test-Path -LiteralPath $IsccPath -PathType Leaf)) {
        throw "Inno Setup compiler does not exist: $IsccPath"
    }
} else {
    $compiler = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($compiler) { $IsccPath = $compiler.Source }
    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramFiles, $env:LOCALAPPDATA)) {
        if ($IsccPath) { break }
        if (-not $base) { continue }
        foreach ($relative in @('Inno Setup 6/ISCC.exe', 'Programs/Inno Setup 6/ISCC.exe')) {
            $candidate = Join-Path $base $relative
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $IsccPath = $candidate; break }
        }
    }
}
if (-not $IsccPath) {
    throw 'Install Inno Setup 6.5+ or set INNO_SETUP_COMPILER to the full path of ISCC.exe.'
}

$GameRoot = Resolve-RwGameRoot -RepoRoot $repoRoot -GameRoot $GameRoot
$payload = Copy-RwGamePayload -RepoRoot $repoRoot -GameRoot $GameRoot
$outputDir = Join-Path $repoRoot 'build/installer'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$setupExe = Join-Path $outputDir 'RWJS-Setup.exe'
if (Test-Path -LiteralPath $setupExe) { Remove-Item -LiteralPath $setupExe -Force }

& $IsccPath "/DAppVersion=$Version" "/DAppSource=$AppSource" "/DGameSource=$payload" `
    "/DRepoRoot=$repoRoot" "/DInstallerOutput=$outputDir" (Join-Path $PSScriptRoot 'RWJS.iss')
if ($LASTEXITCODE -ne 0) { throw "Inno Setup compilation failed: exit code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $setupExe -PathType Leaf)) { throw "Installer was not generated: $setupExe" }
Write-Host "RWJS installer: $setupExe"
