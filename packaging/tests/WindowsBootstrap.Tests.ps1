<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../windows-environment.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $repo ('build/tmp/windows-bootstrap-tests-' + [Guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @('LOCALAPPDATA', 'JAVA_HOME', 'INNO_SETUP_COMPILER', 'ProgramW6432')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    $message = ''
    try { & $Action } catch { $message = $_.Exception.Message }
    Assert-True ($message -match $Pattern) "Expected failure matching '$Pattern'; got '$message'"
}
function Write-Fixture([string]$Path, [string]$Value = 'fixture') {
    New-Item -ItemType Directory -Path (Split-Path $Path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Value)
}

try {
    $env:LOCALAPPDATA = Join-Path $testRoot 'user local data'
    $env:INNO_SETUP_COMPILER = $null
    $env:ProgramW6432 = Join-Path $testRoot 'Program Files 64'
    $customInno = Join-Path $testRoot 'custom installed Inno/ISCC.exe'
    Write-Fixture $customInno
    $Script:RegistryEntries = @([pscustomobject]@{ InstallLocation = (Split-Path $customInno -Parent); AppPath = $null })
    function Get-RwInnoRegistryEntries { return $Script:RegistryEntries }
    $Script:ProbeVersion = '6.7.4'
    $Script:ProbeBroken = $false
    function Get-RwToolProbe([string]$Executable, [string]$Arguments) {
        if ($Arguments -eq '--version') { return [pscustomobject]@{ ExitCode = 0; Output = 'openjdk 21.0.13' } }
        Assert-True ($Arguments -eq '/O- -') 'Inno probe could write an installer or wait for interactive input'
        if ($Script:ProbeBroken) { return [pscustomobject]@{ ExitCode = 1; Output = 'Could not load ISCmplr.dll' } }
        return [pscustomobject]@{ ExitCode = 2; Output = "Inno Setup 6 Command-Line Compiler`nCompiler engine version: Inno Setup $Script:ProbeVersion`nError: [Setup] section missing" }
    }
    # 文本占位 exe 没有文件版本；仍必须从引擎输出识别完整版本，而非只认主版本。
    Assert-True ((Get-RwInnoVersion $customInno) -eq [version]'6.7.4') 'Real engine version was not parsed'
    Assert-True ((Find-RwInnoCompiler) -eq $customInno) 'Registry custom install path was not found'
    $Script:RegistryEntries = @([pscustomobject]@{ InstallLocation = $null; AppPath = (Split-Path $customInno -Parent) })
    Assert-True ((Find-RwInnoCompiler) -eq $customInno) 'Inno Setup: App Path was not found'
    $Script:ProbeVersion = '6.4.3'
    Assert-True (-not (Find-RwInnoCompiler -Candidates @($customInno))) 'Unsupported engine accepted'
    $Script:ProbeVersion = '7.1.1'
    Assert-True ((Find-RwInnoCompiler -Candidates @($customInno)) -eq $customInno) 'Newer compatible Inno rejected'
    $Script:ProbeBroken = $true
    Assert-True (-not (Find-RwInnoCompiler -Candidates @($customInno))) 'Broken compiler accepted'
    $Script:ProbeBroken = $false
    $Script:ProbeVersion = '6.7.4'
    $Script:RegistryEntries = @()
    $programFilesInno = Join-Path $env:ProgramW6432 'Inno Setup 6/ISCC.exe'
    Write-Fixture $programFilesInno
    Assert-True (@(Get-RwInnoCandidates) -contains $programFilesInno) '64-bit Program Files omitted'

    $directInstall = (Get-Item Function:Install-RwWindowsPackageDirect).ScriptBlock
    $saveDownload = (Get-Item Function:Save-RwWindowsDownload).ScriptBlock
    $runInno = (Get-Item Function:Invoke-RwInnoSetup).ScriptBlock
    $Script:WingetPath = $null
    $Script:WingetCode = 0
    $Script:WingetFailure = $null
    $Script:PackageInstalled = $false
    $Script:DirectCalls = 0
    function Find-RwWinget { return $Script:WingetPath }
    function Invoke-RwWingetInstall([string]$WingetPath, [string]$PackageId) {
        if ($Script:WingetFailure) { throw $Script:WingetFailure }
        return $Script:WingetCode
    }
    function Test-RwWindowsPackageInstalled([string]$PackageId) { return $Script:PackageInstalled }
    function Install-RwWindowsPackageDirect([string]$PackageId) { $Script:DirectCalls++ }
    Install-RwWindowsPackage 'JRSoftware.InnoSetup'
    Assert-True ($Script:DirectCalls -eq 1) 'Missing WinGet did not use official fallback'
    $Script:WingetPath = 'winget fixture'
    $Script:WingetFailure = [ComponentModel.Win32Exception]::new(2)
    Install-RwWindowsPackage 'JRSoftware.InnoSetup'
    Assert-True ($Script:DirectCalls -eq 2) 'Broken WinGet alias did not use official fallback'
    $Script:WingetFailure = [ComponentModel.Win32Exception]::new(1223)
    Assert-Throws { Install-RwWindowsPackage 'JRSoftware.InnoSetup' } '.+'
    Assert-True ($Script:DirectCalls -eq 2) 'UAC cancellation was bypassed'
    $Script:WingetFailure = $null
    foreach ($code in @(-1978335189L, 2316632107L, 0L, 3010L, -1978335224L)) {
        $Script:WingetCode = $code
        $before = $Script:DirectCalls
        Install-RwWindowsPackage 'JRSoftware.InnoSetup'
        Assert-True ($Script:DirectCalls -eq $before + 1) 'Unavailable tool did not use fallback after WinGet'
    }
    $Script:PackageInstalled = $true
    $Script:WingetCode = -1978335189L
    $before = $Script:DirectCalls
    Install-RwWindowsPackage 'JRSoftware.InnoSetup'
    Assert-True ($Script:DirectCalls -eq $before) 'No-update exit code unnecessarily reinstalled usable tool'
    foreach ($code in @(1602L, 1223L, 0x800704c7L, 0x8a150005L, 0x8a15010cL, 0x8a150077L, 0x8a150011L)) {
        $Script:WingetCode = $code
        Assert-Throws { Install-RwWindowsPackage 'JRSoftware.InnoSetup' } '失败或被取消'
        Assert-True ($Script:DirectCalls -eq $before) 'Cancellation or hash failure was bypassed'
    }

    # 在实际下载/哈希函数边界模拟网络，不访问网络或执行安装程序。
    $Script:WebCalls = 0
    $Script:DownloadFails = $false
    function Invoke-WebRequest {
        param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile, [string]$ErrorAction)
        $Script:WebCalls++
        Write-Fixture $OutFile 'download fixture'
        if ($Script:DownloadFails) { throw 'fixture network failure' }
    }
    $expectedHash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes('download fixture'))).Replace('-', '')
    $cached = Join-Path $testRoot 'downloads/tool.exe'
    Save-RwWindowsDownload -Uri 'https://example.invalid/tool.exe' -Destination $cached -Sha256 $expectedHash
    Save-RwWindowsDownload -Uri 'https://example.invalid/tool.exe' -Destination $cached -Sha256 $expectedHash
    Assert-True ($Script:WebCalls -eq 1) 'Verified cached download was downloaded again'
    $badFile = Join-Path $testRoot 'downloads/bad.exe'
    Assert-Throws { Save-RwWindowsDownload -Uri 'https://example.invalid/tool.exe' -Destination $badFile -Sha256 ('0' * 64) } 'SHA-256'
    Assert-True (-not (Test-Path -LiteralPath $badFile)) 'Hash-mismatched download was published'
    $Script:DownloadFails = $true
    Assert-Throws { Save-RwWindowsDownload -Uri 'https://example.invalid/tool.exe' -Destination $badFile -Sha256 $expectedHash } 'network failure'
    Assert-True (@(Get-ChildItem -LiteralPath (Split-Path $cached -Parent) -Filter '*.partial').Count -eq 0) 'Partial download was not cleaned'

    # 保留真实解压、查找及安装后校验，只模拟网络与 Windows 原生安装器。
    Set-Item Function:Install-RwWindowsPackageDirect $directInstall
    $Script:DownloadedPackages = [Collections.Generic.List[string]]::new()
    $jdkFixture = Join-Path $testRoot 'jdk-archive/jdk-21.0.13+9'
    foreach ($name in @('bin/java.exe', 'bin/javac.exe', 'bin/jlink.exe', 'bin/jpackage.exe', 'jmods/java.base.jmod')) {
        Write-Fixture (Join-Path $jdkFixture $name)
    }
    Write-Fixture (Join-Path $jdkFixture 'release') "JAVA_VERSION=`"21.0.13`"`nOS_ARCH=`"amd64`""
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function Save-RwWindowsDownload([string]$Uri, [string]$Destination, [string]$Sha256) {
        Assert-True ($Uri -match '^https://github.com/(adoptium/temurin21-binaries|jrsoftware/issrc)/releases/download/') 'Non-official download URL'
        Assert-True ($Sha256 -match '^[a-f0-9]{64}$') 'Missing pinned SHA-256'
        $Script:DownloadedPackages.Add($Destination)
        if ($Destination.EndsWith('.zip')) {
            [IO.Compression.ZipFile]::CreateFromDirectory((Split-Path $jdkFixture -Parent), $Destination)
        } else { Write-Fixture $Destination }
    }
    $Script:SetupCode = 0
    function Invoke-RwInnoSetup([string]$Installer, [string]$Directory, [string]$LogPath) {
        Assert-True ($LogPath.EndsWith('.log')) 'Missing installer log'
        Write-Fixture (Join-Path $Directory 'ISCC.exe')
        return $Script:SetupCode
    }
    Install-RwWindowsPackageDirect 'EclipseAdoptium.Temurin.21.JDK'
    $jdk = $env:JAVA_HOME
    Assert-True ($jdk.StartsWith((Get-RwWindowsToolsRoot)) -and (Find-RwJdk21 -Candidates @($jdk)) -eq $jdk) 'Portable JDK install/validation failed'
    Assert-True (@(Get-RwJdkCandidates) -contains $jdk) 'Portable JDK cannot be rediscovered'
    Remove-Item -LiteralPath (Join-Path $jdkFixture 'bin/jpackage.exe')
    Remove-Item -LiteralPath $Script:DownloadedPackages[0]
    Assert-Throws { Install-RwWindowsPackageDirect 'EclipseAdoptium.Temurin.21.JDK' } '完整性'
    Assert-True ($env:JAVA_HOME -eq $jdk -and (Test-Path -LiteralPath $jdk)) 'Failed JDK install overwrote existing environment/tool'
    Install-RwWindowsPackageDirect 'JRSoftware.InnoSetup'
    $compiler = $env:INNO_SETUP_COMPILER
    Assert-True ($compiler.StartsWith((Get-RwWindowsToolsRoot)) -and (Find-RwInnoCompiler -Candidates @($compiler)) -eq $compiler) 'Portable Inno install/validation failed'
    $env:INNO_SETUP_COMPILER = $null
    Assert-True (@(Get-RwInnoCandidates) -contains $compiler) 'Portable Inno cannot be rediscovered without environment variable'
    $Script:SetupCode = 2
    $innoBefore = @(Get-ChildItem -LiteralPath (Get-RwWindowsToolsRoot) -Directory -Filter 'Inno Setup *').Count
    Assert-Throws { Install-RwWindowsPackageDirect 'JRSoftware.InnoSetup' } '安装失败或被取消'
    Assert-True (@(Get-ChildItem -LiteralPath (Get-RwWindowsToolsRoot) -Directory -Filter 'Inno Setup *').Count -eq $innoBefore) 'Failed Inno install was retained or existing install removed'
    $Script:ProbeBroken = $true
    $Script:SetupCode = 0
    Assert-Throws { Install-RwWindowsPackageDirect 'JRSoftware.InnoSetup' } '运行检查未通过'
    Assert-True (@(Get-ChildItem -LiteralPath (Get-RwWindowsToolsRoot) -Directory -Filter 'Inno Setup *').Count -eq $innoBefore) 'Broken Inno install was retained'
    Assert-True (@(Get-ChildItem -LiteralPath (Get-RwWindowsToolsRoot) -Directory -Filter '.jdk-stage-*').Count -eq 0) 'JDK staging directory was retained'

    Set-Item Function:Invoke-RwInnoSetup $runInno
    $Script:SetupArguments = @()
    function Start-Process {
        param([string]$FilePath, [string[]]$ArgumentList, [switch]$Wait, [switch]$PassThru, [string]$ErrorAction)
        $Script:SetupArguments = $ArgumentList
        Assert-True ($Wait -and $PassThru) 'Did not wait for installer completion'
        $result = [pscustomobject]@{ ExitCode = 0 }
        Add-Member -InputObject $result -MemberType ScriptMethod -Name Dispose -Value { }
        return $result
    }
    $null = Invoke-RwInnoSetup -Installer 'fixture.exe' -Directory 'C:\Users\Maker\Local Tools\Inno' -LogPath 'C:\Users\Maker\Local Tools\install.log'
    foreach ($argument in @('/CURRENTUSER', '/PORTABLE=1', '/NORESTART', '/NOICONS', '/DIR="C:\Users\Maker\Local Tools\Inno"')) {
        Assert-True ($Script:SetupArguments -contains $argument) "Missing safe installer argument: $argument"
    }
    Write-Host 'PASS: Inno engine version/registry paths, WinGet no-update and cancellation, official download hash/cache, portable JDK/Inno install/rediscovery and failure cleanup'
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
