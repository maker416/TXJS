<#
.SYNOPSIS
  Debug 编译、覆盖本机 RWJS，并启动 RWJS.exe。

.EXAMPLE
  .\packaging\debug-desktop-run.ps1
  .\packaging\debug-desktop-run.ps1 -GameRoot D:\RWJS
#>
[CmdletBinding()]
param(
    [string]$GameRoot,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Debug-RwjsLocal.ps1') -Launch -SkipBuild:$SkipBuild -GameRoot $GameRoot
