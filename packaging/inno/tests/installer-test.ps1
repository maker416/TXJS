<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
[CmdletBinding()]
param([string]$IsccPath = $env:INNO_SETUP_COMPILER)
$ErrorActionPreference = 'Stop'
if (-not $IsccPath) { $IsccPath = Join-Path $env:LOCALAPPDATA 'Programs/Inno Setup 6/ISCC.exe' }
if (-not (Test-Path -LiteralPath $IsccPath)) { throw 'Set INNO_SETUP_COMPILER to ISCC.exe before running this test.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$testId = [Guid]::NewGuid().ToString()
$testRoot = [IO.Path]::GetFullPath((Join-Path $repo "build/tmp/installer-test-$testId"))
if (-not $testRoot.StartsWith($repo + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe test path' }
$installDir = Join-Path $testRoot '安装目录 with spaces'
$registryKey = "Software\RWJS-PackagingTests\$testId"
$registryPath = "HKCU:\$registryKey"
$groupName = "RWJS Installer Test $testId"
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{$testId}_is1"
$source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '../RWJS.iss'))
# 执行生产安装脚本，但以独立 AppId、HKCU、测试快捷方式和临时目录隔离真实安装。
$source = $source.Replace('C9F7D3ED-E974-4C19-9291-9D5460608316', $testId).
    Replace('HKLM64', 'HKCU64').Replace('SOFTWARE\RWJS', $registryKey).
    Replace('PrivilegesRequired=admin', 'PrivilegesRequired=lowest').
    Replace('DefaultGroupName=铁锈战争极速版 RWJS', "DefaultGroupName=$groupName").
    Replace('Name: "{group}\铁锈战争极速版 RWJS"', 'Name: "{group}\RWJS Test"').
    Replace('Name: "{autodesktop}\铁锈战争极速版 RWJS"', "Name: `"{autodesktop}\$groupName`"").
    Replace('MessagesFile: "ChineseSimplified.isl"', ('MessagesFile: "' + (Join-Path $repo 'packaging/inno/ChineseSimplified.isl') + '"'))

function Assert-True([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Write-TestFile([string]$Path, [string]$Value = 'fixture') {
    New-Item -ItemType Directory -Path (Split-Path $Path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Value)
}
function Build-TestInstaller([string]$Version) {
    $app = Join-Path $testRoot "source-$Version"
    $game = Join-Path $testRoot 'game'
    New-Item -ItemType Directory -Path $app -Force | Out-Null
    # 不运行游戏：使用系统自带的有效 PE 作为占位入口，所有测试都以静默方式运行。
    Copy-Item -LiteralPath (Join-Path $env:WINDIR 'System32/whoami.exe') -Destination (Join-Path $app 'RWJS.exe')
    Write-TestFile (Join-Path $app 'app/RWJS.cfg')
    Write-TestFile (Join-Path $app "app/dependency-$Version.jar")
    Write-TestFile (Join-Path $app 'runtime/bin/server/jvm.dll')
    Write-TestFile (Join-Path $game 'game-lib.jar')
    $script = Join-Path $testRoot 'fixture.iss'
    [IO.File]::WriteAllText($script, $source, [Text.UTF8Encoding]::new($true))
    $output = Join-Path $testRoot "output-$Version"
    & $IsccPath /Q "/DAppVersion=$Version" "/DAppSource=$app" "/DGameSource=$game" `
        "/DRepoRoot=$repo" "/DInstallerOutput=$output" $script | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Fixture installer compilation failed' }
    return (Join-Path $output 'RWJS-Setup.exe')
}
function Run-TestInstaller([string]$Exe, [string]$ExtraArgs = '') {
    $arguments = "/VERYSILENT /SUPPRESSMSGBOXES /SP- /NORESTART /LOG=`"$testRoot/setup.log`" $ExtraArgs"
    $process = Start-Process -FilePath $Exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    return $process.ExitCode
}

try {
    $first = Build-TestInstaller '0.0.1'
    Assert-True ((Run-TestInstaller $first '/RWJS_UPDATE=1') -ne 0) 'Update without registration was accepted'
    Assert-True (-not (Test-Path -LiteralPath $registryPath)) 'Failed update created installation registration'
    Assert-True ((Run-TestInstaller $first "/DIR=`"$installDir`"") -eq 0) 'Fresh install failed'
    $registered = Get-ItemProperty -LiteralPath $registryPath
    Assert-True ($registered.InstallDir -eq $installDir) 'InstallDir was not registered'
    Assert-True ($registered.InstalledVersion -eq '0.0.1') 'Version was not registered'
    Assert-True (Test-Path -LiteralPath $uninstallKey) 'Windows uninstall entry is missing'
    $desktopShortcut = Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) "$groupName.lnk"
    $menuShortcut = Join-Path ([Environment]::GetFolderPath('Programs')) "$groupName/RWJS Test.lnk"
    Assert-True (Test-Path -LiteralPath $desktopShortcut) 'Desktop shortcut missing'
    Assert-True (Test-Path -LiteralPath $menuShortcut) 'Start menu shortcut missing'
    $userFiles = @('io.github.rwpp.config.Settings.toml', 'saves/user.rwsave', 'replays/user', 'mods/units/user.ini')
    foreach ($relative in $userFiles) { Write-TestFile (Join-Path $installDir $relative) 'user data' }
    # Check directory locking for normal reinstalls. Update explicitly resets /DIR to the registered directory.
    $otherDir = Join-Path $testRoot 'wrong target'
    Assert-True ((Run-TestInstaller $first "/DIR=`"$otherDir`"") -ne 0) 'Second installation directory was accepted'
    $second = Build-TestInstaller '0.0.2'
    Assert-True ((Run-TestInstaller $second "/RWJS_UPDATE=1 /DIR=`"$otherDir`"") -eq 0) 'Registered update failed'
    Assert-True (-not (Test-Path -LiteralPath $otherDir)) 'Update installed to the overridden directory'
    Assert-True ((Get-ItemProperty -LiteralPath $registryPath).InstalledVersion -eq '0.0.2') 'Update did not persist version'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $installDir 'app/dependency-0.0.1.jar'))) 'Old dependency jar survived'
    Assert-True (Test-Path -LiteralPath (Join-Path $installDir 'app/dependency-0.0.2.jar')) 'New dependency jar missing'
    $uninstaller = Join-Path $installDir 'unins000.exe'
    Assert-True ((Run-TestInstaller $uninstaller) -eq 0) 'Uninstall failed'
    Assert-True (-not (Test-Path -LiteralPath $registryPath)) 'Installation registry values survived uninstall'
    Assert-True (-not (Test-Path -LiteralPath $uninstallKey)) 'Windows uninstall entry survived'
    Assert-True (-not (Test-Path -LiteralPath $desktopShortcut)) 'Desktop shortcut survived'
    Assert-True (-not (Test-Path -LiteralPath $menuShortcut)) 'Start menu shortcut survived'
    foreach ($relative in $userFiles) {
        Assert-True (([IO.File]::ReadAllText((Join-Path $installDir $relative))) -eq 'user data') "User data was removed: $relative"
    }
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $installDir 'RWJS.exe'))) 'Installed executable survived uninstall'
    Write-Host 'PASS: fresh install, registration, shortcuts, update locking, stale jars, uninstall and user-data preservation'
} finally {
    $uninstaller = Join-Path $installDir 'unins000.exe'
    if (Test-Path -LiteralPath $uninstaller) { Run-TestInstaller $uninstaller | Out-Null }
    if (Test-Path -LiteralPath $registryPath) { Remove-Item -LiteralPath $registryPath -Recurse -Force }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
