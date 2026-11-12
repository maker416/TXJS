<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../project-version.ps1')
. (Join-Path $PSScriptRoot '../update-packages.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $repo ('build/tmp/release-all-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}
function Write-Fixture([string]$RelativePath, [string]$Content) {
    $path = Join-Path $testRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}

try {
    # 加载真实定义，避免执行顶层入口、修改当前项目版本或启动真实 Gradle。
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../build.ps1'), [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) 'Build entry has syntax errors'
    foreach ($statement in $ast.EndBlock.Statements) {
        if ($statement -is [Management.Automation.Language.FunctionDefinitionAst] -or
            ($statement -is [Management.Automation.Language.AssignmentStatementAst] -and $statement.Left.Extent.Text -eq '$Script:Targets')) {
            . ([scriptblock]::Create($statement.Extent.Text))
        }
    }
    $Script:RootDir = $testRoot
    $Script:BuildDir = Join-Path $testRoot 'build'
    $Script:ArtifactsDir = Join-Path $BuildDir 'artifacts'
    $Script:GradlewBat = Join-Path $testRoot 'gradlew.cmd'
    $Script:Channel = 'official'
    $Script:ChannelPattern = '^[A-Za-z0-9_-]{1,64}$'
    $Script:NoChannelTargets = @('test', 'clean', 'browser-kernels')
    $Script:IsCli = $true
    $Script:GradleCalls = [Collections.Generic.List[object]]::new()
    $Script:EnvironmentChecks = [Collections.Generic.List[object]]::new()
    $Script:FailuresLeft = 0
    $Script:PromptCount = 0
    $Script:OmitApk = $false
    $Script:RejectGame = $false
    $Target = 'release-all'
    $Channel = 'test_cn'
    $Check = $false
    $NoInstall = $true
    $Help = $false
    Write-Fixture 'build.gradle.kts' 'version = "1.20.62"'
    Write-Fixture 'gradlew.cmd' "@exit /b 0`r`n"
    Write-Fixture 'build/key/keystore.properties' 'storeFile=build/key/release.keystore'
    Write-Fixture 'build/key/release.keystore' 'fixture only'

    function Initialize-Environment { return $true }
    function Resolve-RwGameRoot { return (Join-Path $testRoot 'game.zip') }
    function Assert-RwGameSource {
        param($Source, [switch]$Windows)
        if ($Script:RejectGame) { throw 'fixture invalid game archive' }
    }
    function Initialize-RwWindowsBuildEnvironment {
        param([string]$Target, [switch]$AllowInstall)
        $Script:EnvironmentChecks.Add(@{ Target = $Target; AllowInstall = [bool]$AllowInstall })
    }
    function Read-Host {
        $Script:PromptCount++
        return 'test_cn'
    }
    function Invoke-Gradle([string[]]$Tasks) {
        $Script:GradleCalls.Add(@{ Tasks = $Tasks; Version = $Script:Version; Channel = $Script:Channel })
        if ($Script:FailuresLeft -gt 0) { $Script:FailuresLeft--; return 7 }
        Write-Fixture 'build/installer/RWJS-Setup.exe' 'PC installer fixture'
        if (-not $Script:OmitApk) { Write-Fixture 'rwpp-android/build/outputs/apk/release/app-release.apk' 'signed APK fixture' }
        return 0
    }

    foreach ($alias in @('release-all', 'all-release', '8')) {
        Assert-True ((Resolve-Target $alias) -eq 'release-all') "Alias did not resolve: $alias"
    }
    Assert-True ((Resolve-Target 'release') -eq 'android-release') 'Existing release alias changed'
    Assert-True ((Invoke-Main) -eq 0) 'Combined release build failed'
    Assert-True ($Script:Version -eq '1.20.63' -and $Script:GradleCalls.Count -eq 1) 'Version bumped twice or Gradle ran twice'
    $call = $Script:GradleCalls[0]
    Assert-True (($call.Tasks -join ' ') -eq ':rwpp-desktop:packageInnoDistribution :rwpp-android:assembleRelease') 'Wrong Gradle tasks'
    Assert-True ($call.Channel -eq 'test_cn') 'Channel was not shared'
    Assert-True (($Script:EnvironmentChecks.Target -join ',') -eq 'installer,android-release') 'Both targets were not checked before build'
    Assert-True (@($Script:EnvironmentChecks | Where-Object { $_.AllowInstall }).Count -eq 0) 'NoInstall was ignored'
    foreach ($member in @('installer', 'android-release')) {
        $dest = Join-Path $ArtifactsDir "$member-test_cn"
        $info = Get-Content -LiteralPath (Join-Path $dest 'build-info.txt') -Raw
        Assert-True ($info -match 'Version: 1.20.63' -and $info -match 'Channel: test_cn' -and $info -match "Target:  $member") 'Incorrect build metadata'
        $baseName = if ($member -eq 'installer') { 'RWJS-Setup' } else { 'RWJS-Android' }
        Assert-True (Test-Path -LiteralPath (Join-Path $dest "$baseName.zip.001")) "Missing update volume: $member"
        Assert-True (Test-Path -LiteralPath (Join-Path $dest "$baseName.zip.sha256")) "Missing checksum: $member"
    }
    Assert-True (Test-Path -LiteralPath (Join-Path $ArtifactsDir 'installer-test_cn/RWJS-Setup-1.20.63.exe')) 'PC installer missing'
    Assert-True (Test-Path -LiteralPath (Join-Path $ArtifactsDir 'android-release-test_cn/rwpp-android-release-1.20.63.apk')) 'Release APK missing'

    $Check = $true
    $Script:EnvironmentChecks.Clear()
    Assert-True ((Invoke-Main) -eq 0) 'Combined check failed'
    Assert-True ($Script:GradleCalls.Count -eq 1 -and $Script:EnvironmentChecks.Count -eq 2) 'Check executed Gradle or skipped a target'
    Assert-True (@($Script:EnvironmentChecks | Where-Object { $_.AllowInstall }).Count -eq 0) 'Check allowed tool installation'
    $Check = $false

    Remove-Item -LiteralPath (Join-Path $testRoot 'build/key/release.keystore')
    Assert-True ((Invoke-Main) -eq 1 -and $Script:GradleCalls.Count -eq 1) 'Missing signing key did not block both builds'
    Write-Fixture 'build/key/release.keystore' 'fixture only'
    $Script:RejectGame = $true
    Assert-True ((Invoke-Main) -eq 1 -and $Script:GradleCalls.Count -eq 1) 'Invalid PC resources did not block both builds'
    $Script:RejectGame = $false

    $Script:IsCli = $false
    $Script:FailuresLeft = 1
    Assert-True ((Invoke-Target 'release-all') -eq 0) 'Combined build did not recover after retry'
    Assert-True ($Script:PromptCount -eq 1) 'Channel was requested more than once'
    Assert-True ($Script:GradleCalls.Count -eq 3 -and $Script:GradleCalls[1].Version -eq $Script:GradleCalls[2].Version) 'Retry used another version'
    $Script:IsCli = $true
    $Script:FailuresLeft = 2
    Write-Fixture 'build/artifacts/installer-test_cn/previous.txt' 'keep on build failure'
    Assert-True ((Invoke-Target 'release-all') -eq 7) 'Build failure exit code was lost'
    Assert-True (Test-Path -LiteralPath (Join-Path $ArtifactsDir 'installer-test_cn/previous.txt')) 'Failed build replaced artifacts'

    $Script:OmitApk = $true
    Remove-Item -LiteralPath (Join-Path $testRoot 'rwpp-android/build/outputs/apk/release/app-release.apk')
    Assert-True ((Invoke-Target 'release-all') -eq 1) 'Missing APK was reported as a successful release'
    Write-Fixture 'rwpp-android/build/outputs/apk/release/app-release-unsigned.apk' 'unsigned fixture'
    Assert-True ((Invoke-Target 'release-all') -eq 1) 'Unsigned APK was reported as a successful release'

    Write-Host 'PASS: combined release aliases, shared version/channel, both preflights, check/no-install, retry/failure, installer/APK and release volumes'
} finally {
    $safeRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build/tmp')) + [IO.Path]::DirectorySeparatorChar
    if (-not [IO.Path]::GetFullPath($testRoot).StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test directory' }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
