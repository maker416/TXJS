<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../payload.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$testRoot = Join-Path $repo 'build/tmp/payload-tests'
if (-not ([IO.Path]::GetFullPath($testRoot)).StartsWith($repo + [IO.Path]::DirectorySeparatorChar)) {
    throw 'Unsafe test path'
}
if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
$fixture = Join-Path $testRoot 'repo'
$game = Join-Path $testRoot 'original game'

function Write-FixtureFile([string]$Root, [string]$Path, [string]$Value = 'fixture') {
    $target = Join-Path $Root $Path
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($target, $Value)
}
function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}

$savedGameRoot = $env:RW_GAME_ROOT
try {
    $included = @('game-lib.jar', 'lwjgl64.dll', 'steam_appid.txt', 'assets/units/tank.ini',
        'assets/maps/map.tmx', 'font/font.ttf', 'libs/lwjgl.jar', 'res/image.png')
    $excluded = @('RWJS.exe', 'RWPP.exe', 'Rusted Warfare - 64.exe', 'launcher.bat', 'fallback64.bat',
        'runtime/bin/server/jvm.dll', 'jvm64/java.exe', 'generated_lib/core.jar', 'extension/plugin.jar',
        'saves/save.rwsave', 'replays/replay', 'cache/file', 'mods/units/private.ini',
        'mods/maps/private.tmx', 'io.github.rwpp.config.Settings.toml', 'preferences.ini',
        'lastrun.log', 'assets/private.log', 'res/backup.bak', 'libs/android.jar', 'libs/android-game-lib.jar')
    foreach ($path in $included + $excluded) { Write-FixtureFile $game $path }
    Write-FixtureFile $fixture 'packaging/game-root.local.txt' ("# local setting`r`n$game")
    $env:RW_GAME_ROOT = $null
    Assert-True ((Resolve-RwGameRoot $fixture) -eq $game) 'Local path file not resolved'
    $env:RW_GAME_ROOT = 'C:\explicit env path'
    Assert-True ((Resolve-RwGameRoot $fixture) -eq $env:RW_GAME_ROOT) 'Environment should take precedence'
    Assert-True ((Resolve-RwGameRoot $fixture $game) -eq $game) 'Explicit source should take precedence'
    $payload = Copy-RwGamePayload $fixture $game
    foreach ($path in $included) {
        Assert-True (Test-Path -LiteralPath (Join-Path $payload $path)) "Required resource missing: $path"
    }
    foreach ($path in $excluded) {
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $payload $path))) "Private or old launcher file included: $path"
        Assert-True (Test-Path -LiteralPath (Join-Path $game $path)) "Source file was modified: $path"
    }
    # A junction into private data must not be traversed.
    $link = Join-Path $game 'assets/private-link'
    New-Item -ItemType Junction -Path $link -Target (Join-Path $game 'saves') | Out-Null
    Write-FixtureFile $payload 'stale.jar'
    $payload = Copy-RwGamePayload $fixture $game
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $payload 'stale.jar'))) 'Stale staging file survived'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $payload 'assets/private-link'))) 'Junction was followed'
    # Validate overlap before deleting the staging directory.
    Write-FixtureFile $payload 'assets/marker.txt'
    $blocked = $false
    try { Copy-RwGamePayload $fixture $payload | Out-Null } catch { $blocked = $true }
    Assert-True $blocked 'Overlapping source accepted'
    Assert-True (Test-Path -LiteralPath (Join-Path $payload 'assets/marker.txt')) 'Overlap check deleted source'
    $blocked = $false
    try { Copy-RwGamePayload $fixture (Join-Path $testRoot 'missing') | Out-Null } catch { $blocked = $true }
    Assert-True $blocked 'Missing game source accepted'
    Assert-True (Test-Path -LiteralPath (Join-Path $payload 'assets/marker.txt')) 'Missing-source check deleted staging'
    Write-Host 'PASS: required resources, private data exclusion, source preservation, junction and deletion guards'
} finally {
    $env:RW_GAME_ROOT = $savedGameRoot
    # Remove the junction separately so recursive cleanup never follows its target.
    $link = Join-Path $game 'assets/private-link'
    if (Test-Path -LiteralPath $link) { [IO.Directory]::Delete($link) }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
