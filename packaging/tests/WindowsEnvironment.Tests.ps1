<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../windows-environment.ps1')
. (Join-Path $PSScriptRoot '../inno/payload.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $repo ('build/tmp/windows-env-tests-' + [Guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'PATH', 'INNO_SETUP_COMPILER', 'RW_GAME_ARCHIVE', 'RW_GAME_ROOT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}
function Write-Fixture([string]$Path, [string]$Value = 'fixture') {
    New-Item -ItemType Directory -Path (Split-Path $Path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Value)
}
function New-JdkFixture([string]$Directory, [string]$Version = '21.0.13', [string]$Architecture = 'amd64') {
    foreach ($file in @('bin/java.exe', 'bin/javac.exe', 'bin/jlink.exe', 'bin/jpackage.exe', 'jmods/java.base.jmod')) {
        Write-Fixture (Join-Path $Directory $file)
    }
    Write-Fixture (Join-Path $Directory 'release') ((@("JAVA_VERSION=`"$Version`"", "OS_ARCH=`"$Architecture`"")) -join "`n")
}

try {
    $javaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $major = Get-RwJavaMajorVersion $javaCommand.Source
        if ($major) { Assert-True ($major -ge 9) 'Native Java version parsing failed' }
    }

    $goodJdk = Join-Path $testRoot 'JDK with spaces'
    $oldJdk = Join-Path $testRoot 'old JDK'
    $armJdk = Join-Path $testRoot 'ARM JDK'
    $partialJdk = Join-Path $testRoot 'partial JDK'
    New-JdkFixture $goodJdk
    New-JdkFixture $oldJdk '17.0.1'
    New-JdkFixture $armJdk '21.0.13' 'aarch64'
    New-JdkFixture $partialJdk
    Remove-Item -LiteralPath (Join-Path $partialJdk 'bin/jpackage.exe')
    # Windows 二进制不在 Linux 主机执行；只替换两个原生版本探测边界。
    $Script:FixtureJavaMajor = 21
    function Get-RwJavaMajorVersion([string]$JavaExecutable) { return $Script:FixtureJavaMajor }
    Assert-True ((Find-RwJdk21 -Candidates @($oldJdk, $armJdk, $partialJdk, $goodJdk)) -eq $goodJdk) 'JDK fallback/architecture/tool validation failed'
    $Script:FixtureJavaMajor = 17
    Assert-True (-not (Find-RwJdk21 -Candidates @($goodJdk))) 'Wrong running Java version accepted'
    $Script:FixtureJavaMajor = 21

    $goodInno = Join-Path $testRoot 'Inno Setup 6/ISCC.exe'
    $oldInno = Join-Path $testRoot 'old Inno/ISCC.exe'
    Write-Fixture $goodInno
    Write-Fixture $oldInno
    function Get-RwInnoVersion([string]$Compiler) {
        if ($Compiler -eq $oldInno) { return [version]'6.4.0' }
        return [version]'6.7.1'
    }
    Assert-True ((Find-RwInnoCompiler -Candidates @($oldInno, $goodInno)) -eq $goodInno) 'Old Inno version accepted or fallback failed'
    $jdkArguments = @(Get-RwWingetInstallArguments 'EclipseAdoptium.Temurin.21.JDK')
    $innoArguments = @(Get-RwWingetInstallArguments 'JRSoftware.InnoSetup')
    Assert-True ($jdkArguments -contains '--architecture' -and $jdkArguments -contains 'x64') 'JDK install architecture not constrained'
    Assert-True (-not ($innoArguments -contains '--architecture')) 'Inno installer incorrectly constrained to x64'
    foreach ($arguments in @($jdkArguments, $innoArguments)) {
        Assert-True ($arguments -contains '--exact' -and $arguments -contains '--source' -and $arguments -contains 'winget') 'WinGet package/source not constrained'
    }

    $Script:JdksForTest = @($goodJdk)
    $Script:InnoForTest = @($goodInno)
    $Script:InstallCalls = [Collections.Generic.List[string]]::new()
    $Script:InstallShouldFail = $false
    function Get-RwJdkCandidates { return $Script:JdksForTest }
    function Get-RwInnoCandidates { return $Script:InnoForTest }
    function Install-RwWindowsPackage([string]$PackageId) {
        $Script:InstallCalls.Add($PackageId)
        if ($Script:InstallShouldFail) { throw 'fixture installation cancelled' }
        switch ($PackageId) {
            'EclipseAdoptium.Temurin.21.JDK' { $Script:JdksForTest = @($goodJdk) }
            'JRSoftware.InnoSetup' { $Script:InnoForTest = @($goodInno) }
            default { throw 'Unexpected package installation' }
        }
    }
    $env:JAVA_HOME = Join-Path $testRoot 'stale Linux JAVA_HOME'
    $null = Initialize-RwWindowsBuildEnvironment -Target installer
    Assert-True ($env:JAVA_HOME -eq $goodJdk -and $env:INNO_SETUP_COMPILER -eq $goodInno) 'Process environment was not configured'
    Assert-True ($Script:InstallCalls.Count -eq 0) 'Already installed tools caused an installation'
    $null = Initialize-RwWindowsBuildEnvironment -Target installer
    $pathEntries = @($env:PATH -split [regex]::Escape([string][IO.Path]::PathSeparator))
    Assert-True (@($pathEntries | Where-Object { $_ -eq (Join-Path $goodJdk 'bin') }).Count -eq 1) 'JDK PATH entry duplicated'

    $Script:JdksForTest = @()
    $Script:InnoForTest = @()
    $message = ''
    try { Initialize-RwWindowsBuildEnvironment -Target installer | Out-Null } catch { $message = $_.Exception.Message }
    Assert-True ($message -match 'JDK 21' -and $message -match 'Inno Setup') 'Read-only check did not report both missing tools'
    Assert-True ($Script:InstallCalls.Count -eq 0) 'Read-only check installed software'
    $null = Initialize-RwWindowsBuildEnvironment -Target installer -AllowInstall
    Assert-True ($Script:InstallCalls.Count -eq 2) 'Automatic installation did not install exactly the missing tools'
    Assert-True ($Script:InstallCalls[0] -eq 'EclipseAdoptium.Temurin.21.JDK' -and $Script:InstallCalls[1] -eq 'JRSoftware.InnoSetup') 'Unexpected install order/package IDs'
    $Script:InnoForTest = @()
    $Script:InstallCalls.Clear()
    $null = Initialize-RwWindowsBuildEnvironment -Target desktop
    Assert-True ($Script:InstallCalls.Count -eq 0) 'JAR build tried installing Inno Setup'

    $Script:JdksForTest = @()
    $Script:InstallShouldFail = $true
    $blocked = $false
    try { Initialize-RwWindowsBuildEnvironment -Target installer -AllowInstall | Out-Null } catch { $blocked = $true }
    Assert-True $blocked 'Failed/cancelled install was treated as success'

    $fixtureRepo = Join-Path $testRoot 'repo with spaces'
    $adjacent = Join-Path $fixtureRepo 'build/Rusted-Warfare-win.zip'
    $explicit = Join-Path $testRoot 'explicit.zip'
    $fromEnv = Join-Path $testRoot 'env.zip'
    Write-Fixture $adjacent
    Write-Fixture $explicit
    Write-Fixture $fromEnv
    Write-Fixture (Join-Path $fixtureRepo 'packaging/game-archive.local.txt') '/old/linux/path.zip'
    $env:RW_GAME_ARCHIVE = $fromEnv
    $env:RW_GAME_ROOT = $null
    Assert-True ((Resolve-RwGameRoot $fixtureRepo) -eq $adjacent) 'Adjacent ZIP did not override stale machine settings'
    Assert-True ((Resolve-RwGameRoot $fixtureRepo $explicit) -eq $explicit) 'Explicit ZIP did not override adjacent ZIP'
    Remove-Item -LiteralPath $adjacent
    Assert-True ((Resolve-RwGameRoot $fixtureRepo) -eq $fromEnv) 'Archive environment fallback was lost'
    Write-Host 'PASS: JDK/Inno discovery, version/architecture checks, process environment, missing-tool installation, no-install, failure handling and adjacent ZIP priority'
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
