<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../project-version.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $repo ('build/tmp/build-version-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}
function Write-VersionFixture([string]$Content) {
    [IO.File]::WriteAllText((Join-Path $testRoot 'build.gradle.kts'), $Content, [Text.UTF8Encoding]::new($false))
}
function Read-VersionFixture {
    return [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes((Join-Path $testRoot 'build.gradle.kts')))
}

try {
    foreach ($bom in @('', [string][char]0xFEFF)) {
        foreach ($newline in @("`n", "`r`n")) {
            $content = $bom + '// 中文注释' + $newline + 'version = "1.20.99" // release' + $newline
            Write-VersionFixture $content
            $change = Update-RwProjectVersion $testRoot
            Assert-True ($change.Previous -eq '1.20.99' -and $change.Current -eq '1.20.100') 'Patch version did not increase numerically'
            Assert-True ((Read-VersionFixture) -ceq $content.Replace('1.20.99', '1.20.100')) 'BOM, newlines or surrounding text changed'
            $change = Update-RwProjectVersion $testRoot
            Assert-True ($change.Current -eq '1.20.101') 'Second run reused the old version'
        }
    }
    foreach ($content in @('// no version', 'version = "1.2"', 'version = "1.2.3-beta"', 'version = "1.2.03"', "version = `"1.2.3`"`nversion = `"2.3.4`"")) {
        Write-VersionFixture $content
        $failed = $false
        try { Update-RwProjectVersion $testRoot | Out-Null } catch { $failed = $true }
        Assert-True $failed 'Invalid or ambiguous version was accepted'
        Assert-True ((Read-VersionFixture) -ceq $content) 'Invalid version modified the source file'
    }
    Assert-True (@(Get-ChildItem -LiteralPath $testRoot -Filter '*.tmp').Count -eq 0) 'Temporary version file leaked'

    # 加载真实入口的配置/函数，跳过顶层平台转发和 Invoke-Main；只替换工具预检与 Gradle 边界。
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../build.ps1'), [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) 'Build entry has PowerShell syntax errors'
    foreach ($statement in $ast.EndBlock.Statements) {
        if ($statement -is [Management.Automation.Language.FunctionDefinitionAst] -or
            ($statement -is [Management.Automation.Language.AssignmentStatementAst] -and $statement.Left.Extent.Text -eq '$Script:Targets')) {
            . ([scriptblock]::Create($statement.Extent.Text))
        }
    }
    $Script:RootDir = $testRoot
    $Script:BuildDir = Join-Path $testRoot 'build'
    $Script:ArtifactsDir = Join-Path $BuildDir 'artifacts'
    $Script:Channel = 'official'
    $Script:ChannelPattern = '^[A-Za-z0-9_-]{1,64}$'
    $Script:NoChannelTargets = @('test', 'clean', 'browser-kernels')
    $Script:GradlewBat = Join-Path $testRoot 'gradlew.cmd'
    [IO.File]::WriteAllText($GradlewBat, "@exit /b 0`r`n")
    $Script:IsCli = $true
    $Script:PreflightPasses = $true
    $Script:GradleCalls = [Collections.Generic.List[string]]::new()
    $Script:CollectCalls = 0
    $Script:FailuresLeft = 0
    function Test-Preflight([string]$Key) { return $Script:PreflightPasses }
    function Invoke-Gradle([string[]]$Tasks) {
        $Script:GradleCalls.Add((Read-VersionFixture))
        if ($Script:FailuresLeft -gt 0) { $Script:FailuresLeft--; return 7 }
        return 0
    }
    function Invoke-CollectArtifacts([string]$Key) {
        $Script:CollectCalls++
        $dest = Join-Path $ArtifactsDir (Get-OutputSubdir $Key)
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        Write-BuildInfo $dest $Key
    }
    function Test-ProtectedFiles { }
    function Initialize-Environment { return $true }
    Write-VersionFixture 'version = "1.20.62"'
    $Target = 'desktop'
    $Help = $false
    $Check = $false
    $Script:PreflightPasses = $false
    Assert-True ((Invoke-Main) -eq 1) 'Failed preflight was accepted'
    Assert-True ($Script:Version -eq '1.20.63') 'Script startup did not bump before failed preflight'
    $Script:PreflightPasses = $true
    $expectedPatch = 63
    foreach ($key in @('test', 'clean', 'browser-kernels')) {
        $Target = $key
        $expectedPatch++
        Assert-True ((Invoke-Main) -eq 0) "Non-client target failed: $key"
        Assert-True ($Script:Version -eq "1.20.$expectedPatch") "Non-client startup did not bump exactly once: $key"
    }
    $Target = 'desktop'
    $Check = $true
    Assert-True ((Invoke-Main) -eq 0) 'Environment check failed'
    Assert-True ($Script:Version -eq '1.20.67') 'Check startup did not bump'
    $Check = $false
    $Help = $true
    Assert-True ((Invoke-Main) -eq 0) 'Help failed'
    Assert-True ($Script:Version -eq '1.20.68') 'Help startup did not bump'
    $Help = $false
    $Script:IsCli = $false
    function Show-Menu { }
    function Read-Host { return '0' }
    Assert-True ((Invoke-Main) -eq 0) 'Menu exit failed'
    Assert-True ($Script:Version -eq '1.20.69') 'Opening and exiting menu did not bump'
    $Script:IsCli = $true
    Write-VersionFixture 'version = "1.20.62"'
    $Script:GradleCalls.Clear()
    $Script:FailuresLeft = 1
    Assert-True ((Invoke-Main) -eq 0) 'Build did not recover after retry'
    Assert-True ($Script:GradleCalls.Count -eq 2) 'Expected one build retry'
    Assert-True (@($Script:GradleCalls | Where-Object { $_ -notmatch '1.20.63' }).Count -eq 0) 'Gradle did not see the same new version on both attempts'
    Assert-True ((Invoke-Target 'android-release') -eq 0) 'Second client target failed'
    Assert-True ($Script:Version -eq '1.20.63') 'Second target in one run bumped twice'
    $info = Get-Content -LiteralPath (Join-Path $ArtifactsDir 'android-release-official/build-info.txt') -Raw
    Assert-True ($info -match 'Version: 1.20.63') 'Build metadata used an old version'
    $Target = 'installer'
    Assert-True ((Invoke-Main) -eq 0) 'Next run failed'
    Assert-True ($Script:Version -eq '1.20.64') 'Next run did not bump the version'

    $Target = 'desktop'
    $Script:FailuresLeft = 2
    Assert-True ((Invoke-Main) -eq 7) 'Build failure exit code was lost'
    Assert-True ($Script:Version -eq '1.20.65' -and (Read-VersionFixture) -match '1.20.65') 'Failed build did not retain its version'
    Write-VersionFixture 'version = "invalid"'
    $before = $Script:GradleCalls.Count
    Assert-True ((Invoke-Main) -eq 1) 'Invalid version did not stop the build'
    Assert-True ($Script:GradleCalls.Count -eq $before) 'Gradle ran with an invalid version'

    Write-Host 'PASS: startup increments for menu/help/check/test/clean/kernels and failed preflight; retries, shared menu version and metadata'
} finally {
    $safeRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build/tmp')) + [IO.Path]::DirectorySeparatorChar
    if (-not [IO.Path]::GetFullPath($testRoot).StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test directory' }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
