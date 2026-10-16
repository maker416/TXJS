<#
.SYNOPSIS
  Debug 编译并覆盖本机 RWJS（结束已运行进程，不启动游戏）。

.EXAMPLE
  .\packaging\debug-desktop-install.ps1
  .\packaging\debug-desktop-install.ps1 -GameRoot D:\RWJS -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$GameRoot,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Debug-RwjsLocal.ps1') -SkipBuild:$SkipBuild -GameRoot $GameRoot
