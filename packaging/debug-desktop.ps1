<#
.SYNOPSIS
  兼容入口：等同 debug-desktop-run（编译、覆盖安装、启动）。

.EXAMPLE
  .\packaging\debug-desktop.ps1
  .\packaging\debug-desktop.ps1 -NoLaunch   # 仅安装，不启动
#>
[CmdletBinding()]
param(
    [string]$GameRoot = 'D:\RWJS',
    [switch]$SkipBuild,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$params = @{
    GameRoot = $GameRoot
    SkipBuild = $SkipBuild
}
if (-not $NoLaunch) {
    $params['Launch'] = $true
}
& (Join-Path $PSScriptRoot 'Debug-RwjsLocal.ps1') @params
