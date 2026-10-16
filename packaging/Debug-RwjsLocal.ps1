<#
.SYNOPSIS
  本机 RWJS debug：编译 jar、结束已运行进程、按 RWJS.cfg 覆盖 app 内 jar。

.NOTES
  游戏目录默认 D:\RWJS；也可用 -GameRoot、环境变量 RW_GAME_ROOT、或 packaging\game-root.local.txt（已 gitignore）。
#>
[CmdletBinding()]
param(
    [string]$GameRoot,
    [switch]$SkipBuild,
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'

if ($PSScriptRoot -match '[\\/]packaging$') {
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
} else {
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
}

function Resolve-RwjsGameRoot([string]$Override) {
    if ($Override) {
        return [IO.Path]::GetFullPath($Override.Trim().Trim('"'))
    }
    if ($env:RW_GAME_ROOT) {
        return [IO.Path]::GetFullPath($env:RW_GAME_ROOT.Trim().Trim('"'))
    }
    $localFile = Join-Path $repoRoot 'packaging\game-root.local.txt'
    if (Test-Path -LiteralPath $localFile -PathType Leaf) {
        $line = Get-Content -LiteralPath $localFile |
            Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } |
            Select-Object -First 1
        if ($line) {
            return [IO.Path]::GetFullPath($line.Trim().Trim('"'))
        }
    }
    return [IO.Path]::GetFullPath('D:\RWJS')
}

function Stop-RwjsProcess {
    Get-Process -Name 'RWJS' -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "Stopping RWJS (pid $($_.Id))"
        Stop-Process -Id $_.Id -Force
    }
    Start-Sleep -Milliseconds 400
}

function Get-CfgJarName([string]$CfgPath, [string]$Prefix) {
    $like = "*\$Prefix-*.jar"
    $line = Get-Content -LiteralPath $CfgPath |
        Where-Object { $_ -like 'app.classpath=*' -and $_ -like $like } |
        Select-Object -First 1
    if (-not $line) { return $null }
    if ($line -match '\\([^\\]+\.jar)\s*$') { return $Matches[1] }
    return $null
}

function Install-RwjsDebugJars {
    param(
        [string]$Root,
        [bool]$DoBuild
    )

    Set-Location -LiteralPath $repoRoot

    $resolved = Resolve-RwjsGameRoot $Root
    $exe = Join-Path $resolved 'RWJS.exe'
    $appDir = Join-Path $resolved 'app'
    $cfg = Join-Path $appDir 'RWJS.cfg'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        throw "RWJS.exe not found: $exe"
    }
    if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) {
        throw "Missing app\RWJS.cfg under $cfg (install full desktop package first)."
    }

    $localRootFile = Join-Path $repoRoot 'packaging\game-root.local.txt'
    Set-Content -LiteralPath $localRootFile -Value $resolved -Encoding utf8
    Write-Host ('game-root.local.txt -> ' + $resolved)

    if ($DoBuild) {
        $gradlew = Join-Path $repoRoot 'gradlew.bat'
        Write-Host 'Building debug jars (rwpp-desktop, core, core-api, android-compat)...'
        & $gradlew ':rwpp-desktop:jar' ':rwpp-core:desktopJar' ':rwpp-core-api:jar' ':rwpp-desktop:desktopAndroidCompatJar' --quiet
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed, exit $LASTEXITCODE" }
    }

    Stop-RwjsProcess

    $copies = @(
        @{ Prefix = 'rwpp-desktop'; Source = Join-Path $repoRoot 'rwpp-desktop\build\libs\rwpp-desktop.jar' }
        @{ Prefix = 'rwpp-core-desktop'; Source = Join-Path $repoRoot 'rwpp-core\build\libs\rwpp-core-desktop.jar' }
        @{ Prefix = 'rwpp-core-api'; Source = Join-Path $repoRoot 'rwpp-core-api\build\libs\rwpp-core-api.jar' }
        @{ Prefix = 'rwjs-android-compat'; Source = Join-Path $repoRoot 'rwpp-desktop\build\generated\desktop-compat\rwjs-android-compat.jar' }
    )

    foreach ($item in $copies) {
        if (-not (Test-Path -LiteralPath $item.Source -PathType Leaf)) {
            throw "Missing build output: $($item.Source)"
        }
        $destName = Get-CfgJarName $cfg $item.Prefix
        if (-not $destName) {
            throw "RWJS.cfg has no entry for $($item.Prefix)-*.jar"
        }
        $dest = Join-Path $appDir $destName
        Copy-Item -LiteralPath $item.Source -Destination $dest -Force
        Write-Host "Updated $destName"
    }

    return @{
        GameRoot = $resolved
        Exe = $exe
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $info = Install-RwjsDebugJars -Root $GameRoot -DoBuild (-not $SkipBuild)
    if ($Launch) {
        Write-Host "Launching $($info.Exe)"
        Start-Process -FilePath $info.Exe -WorkingDirectory $info.GameRoot
        Write-Host 'RWJS started.'
    } else {
        Write-Host ('Installed to ' + $info.GameRoot + ' (not launched)')
    }
}
