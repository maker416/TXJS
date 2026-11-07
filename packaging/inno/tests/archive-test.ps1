<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../payload.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$testRoot = Join-Path $repo ('build/tmp/archive-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}
function New-FixtureArchive([string]$Path, [string]$Prefix, [string[]]$Names) {
    $archive = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Names) {
            $entry = $archive.CreateEntry($Prefix + $name)
            $writer = [IO.StreamWriter]::new($entry.Open())
            try { $writer.Write($name) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
}

$savedRoot = $env:RW_GAME_ROOT
$savedArchive = $env:RW_GAME_ARCHIVE
try {
    $fixture = Join-Path $testRoot 'repo'
    $included = @('game-lib.jar', 'assets/units/tank.ini', 'lwjgl64.dll', 'libRocketCore.so.1', 'libs/lwjgl.jar')
    $excluded = @('saves/private.rwsave', 'mods/units/private.ini', 'jvm-linux/bin/java', 'libs/android.jar', 'preferences.ini')
    $index = 0
    foreach ($prefix in @('', 'Rusted Warfare/')) {
        $zip = Join-Path $testRoot ("original game $index.zip")
        New-FixtureArchive $zip $prefix ($included + $excluded)
        $before = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
        Assert-RwGameSource -Source $zip -Windows
        $dest = Copy-RwGamePayload -RepoRoot $fixture -GameRoot $zip
        foreach ($name in $included) { Assert-True (Test-Path -LiteralPath (Join-Path $dest $name)) "Missing: $name" }
        foreach ($name in $excluded) { Assert-True (-not (Test-Path -LiteralPath (Join-Path $dest $name))) "Private data included: $name" }
        Assert-True ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -eq $before) 'Source ZIP changed'
        $env:RW_GAME_ROOT = 'old-directory'
        $env:RW_GAME_ARCHIVE = $zip
        Assert-True ((Resolve-RwGameRoot -RepoRoot $fixture) -eq $zip) 'ZIP env precedence failed'
        $index++
    }
    $marker = Join-Path $dest 'marker.txt'
    [IO.File]::WriteAllText($marker, 'keep')
    foreach ($bad in @('../escape', '/absolute', 'C:/drive', 'assets/../../escape', 'assets\..\escape', 'assets/units/TANK.INI')) {
        $zip = Join-Path $testRoot ("bad-$index.zip")
        New-FixtureArchive $zip '' ($included + @($bad))
        $blocked = $false
        try { Copy-RwGamePayload -RepoRoot $fixture -GameRoot $zip | Out-Null } catch { $blocked = $true }
        Assert-True $blocked "Unsafe archive accepted: $bad"
        Assert-True (Test-Path -LiteralPath $marker) 'Invalid ZIP deleted existing staging'
        $index++
    }
    $zip = Join-Path $testRoot 'linux-only.zip'
    New-FixtureArchive $zip '' @('game-lib.jar', 'assets/units/tank.ini', 'liblwjgl64.so')
    $blocked = $false
    try { Assert-RwGameSource -Source $zip -Windows } catch { $blocked = $true }
    Assert-True $blocked 'Windows installer accepted Linux-only native libraries'
    Write-Host 'PASS: flat/wrapped ZIP, resources, private data exclusion, source preservation, unsafe paths and platform validation'
} finally {
    $env:RW_GAME_ROOT = $savedRoot
    $env:RW_GAME_ARCHIVE = $savedArchive
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
