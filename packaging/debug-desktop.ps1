<#
.SYNOPSIS
  把当前仓库编成桌面 debug，覆盖安装到本机 RWJS 目录并启动。

.DESCRIPTION
  不走 Gradle :run（$ROOTDIR 参数只给安装包用）。
  只重编并替换 app 里的 rwpp-desktop / rwpp-core / rwpp-core-api / android-compat jar，
  再启动已有的 RWJS.exe。默认安装目录 D:\RWJS。

.EXAMPLE
  .\packaging\debug-desktop.ps1
  .\packaging\debug-desktop.ps1 -GameRoot D:\RWJS -NoLaunch
  .\packaging\debug-desktop.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$GameRoot = 'D:\RWJS',
    [switch]$SkipBuild,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $repoRoot

if (-not $GameRoot) { $GameRoot = $env:RW_GAME_ROOT }
if (-not $GameRoot) { $GameRoot = 'D:\RWJS' }
$GameRoot = [IO.Path]::GetFullPath($GameRoot.Trim().Trim('"'))

$exe = Join-Path $GameRoot 'RWJS.exe'
$appDir = Join-Path $GameRoot 'app'
$cfg = Join-Path $appDir 'RWJS.cfg'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw "找不到 RWJS.exe: $exe"
}
if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) {
    throw "找不到 app\RWJS.cfg: $cfg"
}

$localRootFile = Join-Path $repoRoot 'packaging\game-root.local.txt'
Set-Content -LiteralPath $localRootFile -Value $GameRoot -Encoding utf8
Write-Host "game-root.local.txt -> $GameRoot"

function Stop-Rwjs {
    Get-Process -Name 'RWJS' -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "停止已运行的 RWJS (pid $($_.Id))"
        Stop-Process -Id $_.Id -Force
    }
    Start-Sleep -Milliseconds 400
}

function Get-CfgJar([string]$Prefix) {
    $line = Select-String -LiteralPath $cfg -Pattern ("app\.classpath=\`$APPDIR\\{0}-[^\s]+\.jar" -f [regex]::Escape($Prefix)) |
        Select-Object -First 1
    if (-not $line) { return $null }
    if ($line.Line -match '\\([^\\]+\.jar)\s*$') { return $Matches[1] }
    return $null
}

if (-not $SkipBuild) {
    $gradlew = Join-Path $repoRoot 'gradlew.bat'
    Write-Host '编译 :rwpp-desktop:jar（含 core / core-api）...'
    & $gradlew ':rwpp-desktop:jar' ':rwpp-core:desktopJar' ':rwpp-core-api:jar' ':rwpp-desktop:desktopAndroidCompatJar' --quiet
    if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败，exit $LASTEXITCODE" }
}

$copies = @(
    @{ Prefix = 'rwpp-desktop'; Source = Join-Path $repoRoot 'rwpp-desktop\build\libs\rwpp-desktop.jar' }
    @{ Prefix = 'rwpp-core-desktop'; Source = Join-Path $repoRoot 'rwpp-core\build\libs\rwpp-core-desktop.jar' }
    @{ Prefix = 'rwpp-core-api'; Source = Join-Path $repoRoot 'rwpp-core-api\build\libs\rwpp-core-api.jar' }
    @{ Prefix = 'rwjs-android-compat'; Source = Join-Path $repoRoot 'rwpp-desktop\build\generated\desktop-compat\rwjs-android-compat.jar' }
)

Stop-Rwjs

foreach ($item in $copies) {
    if (-not (Test-Path -LiteralPath $item.Source -PathType Leaf)) {
        throw "缺少编译产物: $($item.Source)"
    }
    $destName = Get-CfgJar $item.Prefix
    if (-not $destName) {
        throw "RWJS.cfg 中找不到 $($item.Prefix)-*.jar，请先用完整安装包装过一次。"
    }
    $dest = Join-Path $appDir $destName
    Copy-Item -LiteralPath $item.Source -Destination $dest -Force
    Write-Host "覆盖 $destName"
}

if ($NoLaunch) {
    Write-Host "已安装到 $GameRoot（未启动）"
    return
}

Write-Host "启动 $exe"
Start-Process -FilePath $exe -WorkingDirectory $GameRoot
Write-Host '桌面 debug 已启动。之后改 UI 再跑本脚本即可。'
