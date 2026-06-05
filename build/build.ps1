<#
.SYNOPSIS
    RWPP/TXJS 构建工具（PowerShell 版）。

.DESCRIPTION
    在项目根目录调用 gradlew 执行构建，并在成功后把产物收集到 build\artifacts\<子目录> 下。
    支持交互式菜单与命令行参数两种方式。

.PARAMETER Target
    构建目标。可选：desktop / msi / test / android-debug / android-release / clean
    省略时进入交互式菜单。

.EXAMPLE
    .\build.ps1                 # 打开交互式菜单
    .\build.ps1 desktop         # 构建桌面端 fat jar
    .\build.ps1 msi             # 构建 Windows MSI 安装包
    .\build.ps1 clean           # 受保护的 Gradle clean
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Target,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

# 统一控制台输出为 UTF-8，保证中文正常显示
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ---------------------------------------------------------------------------
# 路径与全局配置
# ---------------------------------------------------------------------------
$Script:BuildDir     = $PSScriptRoot
$Script:RootDir      = (Resolve-Path (Join-Path $BuildDir '..')).Path
$Script:ArtifactsDir = Join-Path $BuildDir 'artifacts'
$Script:GradlewBat   = Join-Path $RootDir 'gradlew.bat'
$Script:GradleArgs   = @('--no-daemon', '--parallel', '--build-cache', '--configure-on-demand')
$Script:IsCli        = [bool]$PSBoundParameters.ContainsKey('Target') -and -not [string]::IsNullOrWhiteSpace($Target)
$Script:Version      = 'unknown'

# ---------------------------------------------------------------------------
# 目标定义表
# ---------------------------------------------------------------------------
$Script:Targets = [ordered]@{
    'desktop' = @{
        Name          = 'Desktop fat jar'
        Tasks         = @(':rwpp-desktop:packageReleaseUberJarForCurrentOS')
        Subdir        = 'desktop-jar'
        Collect       = 'desktop'
        RequireDotnet = $false
        Aliases       = @('1', 'jar', 'fatjar')
    }
    'msi' = @{
        Name          = 'Windows MSI installer'
        Tasks         = @(':rwpp-desktop:packageWixDistribution')
        Subdir        = 'msi'
        Collect       = 'msi'
        RequireDotnet = $true
        Aliases       = @('2', 'installer')
    }
    'test' = @{
        Name          = 'Tests'
        Tasks         = @(':rwpp-core:test', ':rwpp-core-api:test')
        Subdir        = 'test-reports'
        Collect       = 'test'
        RequireDotnet = $false
        Aliases       = @('3', 'tests')
    }
    'android-debug' = @{
        Name          = 'Android Debug APK'
        Tasks         = @(':rwpp-android:assembleDebug')
        Subdir        = 'android-debug'
        Collect       = 'android-debug'
        RequireDotnet = $false
        Aliases       = @('4', 'debug', 'apk-debug')
    }
    'android-release' = @{
        Name          = 'Android Release APK'
        Tasks         = @(':rwpp-android:assembleRelease')
        Subdir        = 'android-release'
        Collect       = 'android-release'
        RequireDotnet = $false
        Aliases       = @('5', 'release', 'apk-release')
    }
    'clean' = @{
        Name          = 'Safe clean'
        Tasks         = @('clean')
        Subdir        = $null
        Collect       = 'none'
        RequireDotnet = $false
        Aliases       = @('6', 'safe-clean')
    }
}

# ---------------------------------------------------------------------------
# 日志辅助
# ---------------------------------------------------------------------------
function Write-Info  ([string]$Msg) { Write-Host "[INFO] $Msg"      -ForegroundColor Cyan }
function Write-Ok    ([string]$Msg) { Write-Host "[DONE] $Msg"      -ForegroundColor Green }
function Write-Warn  ([string]$Msg) { Write-Host "[WARN] $Msg"      -ForegroundColor Yellow }
function Write-Err   ([string]$Msg) { Write-Host "[ERROR] $Msg"     -ForegroundColor Red }
function Write-Step  ([string]$Msg) { Write-Host "[ARTIFACTS] $Msg" -ForegroundColor Magenta }

# ---------------------------------------------------------------------------
# 启动检查
# ---------------------------------------------------------------------------
function Initialize-Environment {
    if (-not (Test-Path -LiteralPath $RootDir -PathType Container)) {
        Write-Err "项目根目录不存在: $RootDir"; return $false
    }
    if (-not (Test-Path -LiteralPath $GradlewBat -PathType Leaf)) {
        Write-Err "未找到 gradlew.bat，请把脚本放在 TXJS\build\ 下。"; return $false
    }
    if (-not (Test-Path -LiteralPath $ArtifactsDir -PathType Container)) {
        New-Item -ItemType Directory -Path $ArtifactsDir -Force | Out-Null
    }
    Push-Location -LiteralPath $RootDir
    return $true
}

function Read-ProjectVersion {
    $gradleFile = Join-Path $RootDir 'build.gradle.kts'
    if (Test-Path -LiteralPath $gradleFile) {
        $line = Select-String -LiteralPath $gradleFile -Pattern '^\s*version\s*=\s*"([^"]+)"' | Select-Object -First 1
        if ($line) { $Script:Version = $line.Matches[0].Groups[1].Value }
    }
}

# ---------------------------------------------------------------------------
# 目标解析
# ---------------------------------------------------------------------------
function Resolve-Target ([string]$Arg) {
    if ([string]::IsNullOrWhiteSpace($Arg)) { return $null }
    $key = $Arg.Trim().ToLowerInvariant()

    if ($Targets.Contains($key)) { return $key }
    foreach ($name in $Targets.Keys) {
        if ($Targets[$name].Aliases -contains $key) { return $name }
    }
    return $null
}

# ---------------------------------------------------------------------------
# 界面
# ---------------------------------------------------------------------------
function Show-Header {
    Write-Host '------------------------------------------------------------'
    Write-Host '  RWPP/TXJS Build Tool (PowerShell)'
    Write-Host "  Version: $Version  |  Root: $RootDir"
    Write-Host '  Mode: 默认增量构建，产物收集到 build\artifacts\'
    Write-Host '------------------------------------------------------------'
    Write-Host ''
}

function Show-Menu {
    Clear-Host
    Show-Header
    Write-Host '  [1] Desktop fat jar'
    Write-Host '      Task:   :rwpp-desktop:packageReleaseUberJarForCurrentOS'
    Write-Host '      Output: build\artifacts\desktop-jar\'
    Write-Host ''
    Write-Host '  [2] Windows MSI installer (需要 .NET SDK)'
    Write-Host '      Task:   :rwpp-desktop:packageWixDistribution'
    Write-Host '      Output: build\artifacts\msi\'
    Write-Host ''
    Write-Host '  [3] Tests (rwpp-core / rwpp-core-api)'
    Write-Host '      Task:   :rwpp-core:test :rwpp-core-api:test'
    Write-Host '      Output: build\artifacts\test-reports\'
    Write-Host ''
    Write-Host '  [4] Android Debug APK'
    Write-Host '      Task:   :rwpp-android:assembleDebug'
    Write-Host '      Output: build\artifacts\android-debug\'
    Write-Host ''
    Write-Host '  [5] Android Release APK (签名配置可选)'
    Write-Host '      Task:   :rwpp-android:assembleRelease'
    Write-Host '      Output: build\artifacts\android-release\'
    Write-Host ''
    Write-Host '  [6] Safe clean (保留 build\build.* / build\key / build\artifacts)'
    Write-Host '      Task:   clean'
    Write-Host ''
    Write-Host '  [H] Help    [0] Exit'
    Write-Host ''
    Write-Host '------------------------------------------------------------'
}

function Show-Usage {
    Show-Header
    Write-Host 'Usage:'
    Write-Host '  build.ps1                 打开交互式菜单'
    Write-Host '  build.ps1 desktop         构建桌面端 fat jar'
    Write-Host '  build.ps1 msi             构建 Windows MSI 安装包'
    Write-Host '  build.ps1 test            运行核心测试'
    Write-Host '  build.ps1 android-debug   构建 Android Debug APK'
    Write-Host '  build.ps1 android-release 构建 Android Release APK'
    Write-Host '  build.ps1 clean           运行受保护的 Gradle clean'
    Write-Host ''
    Write-Host 'Aliases:'
    Write-Host '  desktop, jar, fatjar'
    Write-Host '  msi, installer'
    Write-Host '  test, tests'
    Write-Host '  android-debug, debug, apk-debug'
    Write-Host '  android-release, release, apk-release'
    Write-Host '  clean, safe-clean'
    Write-Host ''
    Write-Host 'Notes:'
    Write-Host '  - 构建目标不会自动执行 clean。'
    Write-Host '  - 需要清理时单独运行 clean 目标。'
    Write-Host '  - clean 前会校验 build.gradle.kts 中的根目录保护规则。'
}

# ---------------------------------------------------------------------------
# 预检
# ---------------------------------------------------------------------------
function Test-Preflight ([string]$Key) {
    $def = $Targets[$Key]

    if ($def.RequireDotnet) {
        if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
            Write-Err 'MSI 构建需要 .NET SDK，但 PATH 中找不到 dotnet。请安装 .NET SDK 或选择其他目标。'
            return $false
        }
    }

    if ($Key -eq 'android-release') {
        $ksProps = Join-Path $RootDir 'build\key\keystore.properties'
        if (-not (Test-Path -LiteralPath $ksProps)) {
            Write-Err 'Release 构建需要 build\key\keystore.properties。'
            Write-Info '请运行: powershell -File build\generate-keystore.ps1'
            Write-Info '或复制 build\key\keystore.properties.example 并填入密钥信息。'
            return $false
        }
        $storeFileLine = Get-Content -LiteralPath $ksProps | Where-Object { $_ -match '^storeFile=' } | Select-Object -First 1
        if (-not $storeFileLine) {
            Write-Err 'keystore.properties 缺少 storeFile 配置。'
            return $false
        }
        $storeRel = ($storeFileLine -replace '^storeFile=', '').Trim()
        $storePath = Join-Path $RootDir ($storeRel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $storePath)) {
            Write-Err "密钥库文件不存在: $storePath"
            Write-Info '请运行: powershell -File build\generate-keystore.ps1'
            return $false
        }
    }

    if ($Key -eq 'clean') {
        if (-not (Test-CleanProtection)) { return $false }
    }
    return $true
}

function Test-CleanProtection {
    $gradleFile = Join-Path $RootDir 'build.gradle.kts'
    if (-not (Test-Path -LiteralPath $gradleFile)) {
        Write-Err "未找到 build.gradle.kts: $gradleFile"; return $false
    }
    $content = Get-Content -LiteralPath $gradleFile -Raw
    $required = @('key/**', 'artifacts/**', '*.bat', '*.cmd', '*.ps1')
    $missing = $required | Where-Object { $content -notlike "*$_*" }
    if ($missing.Count -gt 0) {
        Write-Err 'build.gradle.kts 缺少根目录保护规则。'
        Write-Err ('         需要排除: {0}' -f ($missing -join ', '))
        return $false
    }
    return $true
}

# ---------------------------------------------------------------------------
# 执行 Gradle
# ---------------------------------------------------------------------------
function Invoke-Gradle ([string[]]$Tasks) {
    $allArgs = $GradleArgs + $Tasks
    Write-Host "[Gradle] $GradlewBat $($allArgs -join ' ')" -ForegroundColor DarkGray
    Write-Host ''
    # 通过 Out-Host 让 Gradle 输出直接写入终端，避免它混入函数返回值（success stream），从而保证退出码判断正确
    & $GradlewBat @allArgs | Out-Host
    return $LASTEXITCODE
}

function Invoke-Target ([string]$Key) {
    $def = $Targets[$Key]

    if (-not (Test-Preflight $Key)) { return 1 }

    Write-Host ''
    Write-Host '------------------------------------------------------------'
    Write-Host "  Target:  $($def.Name)"
    Write-Host "  Version: $Version"
    Write-Host "  Tasks:   $($def.Tasks -join ' ')"
    if ($def.Subdir) { Write-Host "  Output:  build\artifacts\$($def.Subdir)\" }
    Write-Host '------------------------------------------------------------'
    Write-Host ''
    if ($Key -eq 'clean') {
        Write-Host '[CLEAN] 运行 Gradle clean（已启用根目录保护）。'
    } else {
        Write-Host '[BUILD] 不会自动执行 clean，需要时请单独运行 clean 目标。'
    }
    Write-Host ''

    $code = Invoke-Gradle $def.Tasks

    if ($code -ne 0) {
        Write-Host ''
        Write-Warn '构建失败，停止 Gradle Daemon 后重试一次...'
        & $GradlewBat '--stop' 2>&1 | Out-Null
        $code = Invoke-Gradle $def.Tasks
    }

    Write-Host ''
    if ($code -eq 0) {
        Write-Ok "$($def.Name) 构建成功。"
        if ($Key -eq 'clean') {
            Test-ProtectedFiles
        } else {
            Invoke-CollectArtifacts $Key
        }
    } else {
        Write-Err "$($def.Name) 失败，退出码: $code"
    }
    return $code
}

# ---------------------------------------------------------------------------
# 产物收集
# ---------------------------------------------------------------------------
function Reset-Destination ([string]$Subdir) {
    $dest = Join-Path $ArtifactsDir $Subdir
    if (Test-Path -LiteralPath $dest) {
        # 仅清空目录内容而非删除目录本身，避免目录被资源管理器/杀毒等句柄占用时整体失败
        Get-ChildItem -LiteralPath $dest -Force -ErrorAction SilentlyContinue | ForEach-Object {
            try {
                Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction Stop
            } catch {
                Write-Warn "无法删除旧产物，将尝试直接覆盖: $($_.Name)"
            }
        }
    } else {
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
    }
    return $dest
}

function Write-BuildInfo ([string]$Dest, [string]$Key) {
    $def = $Targets[$Key]
    $lines = @(
        'RWPP/TXJS Build Info'
        '===================='
        "Version: $Version"
        "Target:  $Key"
        "Name:    $($def.Name)"
        "Task:    $($def.Tasks -join ' ')"
        "BuiltAt: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        "Root:    $RootDir"
    )
    Set-Content -LiteralPath (Join-Path $Dest 'build-info.txt') -Value $lines -Encoding UTF8
}

function Invoke-CollectArtifacts ([string]$Key) {
    $def = $Targets[$Key]
    Write-Host ''
    Write-Step '正在收集产物到 build\artifacts\ ...'

    # 收集产物失败不应推翻"构建成功"的结论，这里整体容错，仅给出警告
    try {
        switch ($def.Collect) {
            'desktop'         { Copy-DesktopJar  $Key }
            'msi'             { Copy-MsiFiles    $Key }
            'test'            { Copy-TestReports $Key }
            'android-debug'   { Copy-Apk $Key 'rwpp-android\build\outputs\apk\debug'   'rwpp-android-debug' }
            'android-release' { Copy-Apk $Key 'rwpp-android\build\outputs\apk\release' 'rwpp-android-release' }
            default           { }
        }
    } catch {
        Write-Warn "产物收集时出现问题（不影响构建结果）: $($_.Exception.Message)"
    }
}

function Copy-DesktopJar ([string]$Key) {
    $dest = Reset-Destination $Targets[$Key].Subdir
    $candidates = @(
        'rwpp-desktop\build\compose\jars'
        'rwpp-desktop\build\compose-jars'
        'rwpp-desktop\build\libs'
    )
    $jars = @()
    foreach ($rel in $candidates) {
        $full = Join-Path $RootDir $rel
        if (Test-Path -LiteralPath $full) {
            $jars += Get-ChildItem -LiteralPath $full -Filter '*.jar' -File -ErrorAction SilentlyContinue
        }
    }
    if ($jars.Count -eq 0) {
        Write-Warn '未找到桌面端 jar，请检查 rwpp-desktop\build\compose\jars\。'
        return
    }
    foreach ($jar in $jars) {
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $dest $jar.Name) -Force
    }
    Write-BuildInfo $dest $Key
    Write-Step "桌面端 jar 已复制到: $dest"
    Get-ChildItem -LiteralPath $dest -Filter '*.jar' | ForEach-Object { Write-Host "  $($_.Name)" }
}

function Copy-MsiFiles ([string]$Key) {
    $dest = Reset-Destination $Targets[$Key].Subdir
    $copied = $false

    $setupExe = Join-Path $RootDir 'RWPP-Setup.exe'
    if (Test-Path -LiteralPath $setupExe) {
        Copy-Item -LiteralPath $setupExe -Destination (Join-Path $dest "RWPP-Setup-$Version.exe") -Force
        Copy-Item -LiteralPath $setupExe -Destination (Join-Path $dest 'RWPP-Setup.exe') -Force
        $copied = $true
    }
    $msi = Join-Path $RootDir 'RWPP.msi'
    if (Test-Path -LiteralPath $msi) {
        Copy-Item -LiteralPath $msi -Destination (Join-Path $dest "RWPP-$Version.msi") -Force
        Copy-Item -LiteralPath $msi -Destination (Join-Path $dest 'RWPP.msi') -Force
        $copied = $true
    }
    $pdb = Join-Path $RootDir 'RWPP-Setup.pdb'
    if (Test-Path -LiteralPath $pdb) {
        Copy-Item -LiteralPath $pdb -Destination $dest -Force
    }

    if (-not $copied) {
        Write-Warn '未在项目根目录找到 RWPP-Setup.exe / RWPP.msi。'
        return
    }
    Write-BuildInfo $dest $Key
    Write-Step "MSI 文件已复制到: $dest"
    Get-ChildItem -LiteralPath $dest -Filter 'RWPP*' | ForEach-Object { Write-Host "  $($_.Name)" }
}

function Copy-TestReports ([string]$Key) {
    $dest = Reset-Destination $Targets[$Key].Subdir
    $map = @(
        @{ Src = 'rwpp-core\build\reports\tests';          Out = 'rwpp-core-reports' }
        @{ Src = 'rwpp-core\build\test-results\test';      Out = 'rwpp-core-results' }
        @{ Src = 'rwpp-core-api\build\reports\tests';      Out = 'rwpp-core-api-reports' }
        @{ Src = 'rwpp-core-api\build\test-results\test';  Out = 'rwpp-core-api-results' }
    )
    $copied = $false
    foreach ($item in $map) {
        $full = Join-Path $RootDir $item.Src
        if (Test-Path -LiteralPath $full) {
            Copy-Item -LiteralPath $full -Destination (Join-Path $dest $item.Out) -Recurse -Force
            $copied = $true
        }
    }
    if (-not $copied) {
        Write-Warn '未找到测试报告，请检查 rwpp-core\build\reports\tests。'
        return
    }
    Write-BuildInfo $dest $Key
    Write-Step "测试报告已复制到: $dest"
}

function Copy-Apk ([string]$Key, [string]$RelDir, [string]$BaseName) {
    $dest = Reset-Destination $Targets[$Key].Subdir
    $full = Join-Path $RootDir $RelDir
    $apks = @()
    if (Test-Path -LiteralPath $full) {
        $apks = Get-ChildItem -LiteralPath $full -Filter '*.apk' -File -ErrorAction SilentlyContinue
    }
    if ($apks.Count -eq 0) {
        Write-Warn "未找到 APK，请检查 $RelDir\。"
        return
    }
    foreach ($apk in $apks) {
        Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $dest "$BaseName-$Version.apk") -Force
        Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $dest $apk.Name) -Force
    }
    Write-BuildInfo $dest $Key
    Write-Step "APK 已复制到: $dest"
    Get-ChildItem -LiteralPath $dest -Filter '*.apk' | ForEach-Object { Write-Host "  $($_.Name)" }
}

function Test-ProtectedFiles {
    $scriptSelf = Join-Path $BuildDir 'build.ps1'
    if (Test-Path -LiteralPath $scriptSelf) {
        Write-Host '[PROTECT] build\build.ps1 仍然存在。' -ForegroundColor Green
    } else {
        Write-Err 'build\build.ps1 被 clean 删除了，请检查 build.gradle.kts。'
    }
    if (Test-Path -LiteralPath (Join-Path $BuildDir 'key'))       { Write-Host '[PROTECT] build\key\ 仍然存在。' -ForegroundColor Green }
    if (Test-Path -LiteralPath $ArtifactsDir)                     { Write-Host '[PROTECT] build\artifacts\ 仍然存在。' -ForegroundColor Green }
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
function Invoke-Main {
    if (-not (Initialize-Environment)) { return 1 }
    Read-ProjectVersion

    if ($Help -or $Target -in @('help', '--help', '/?')) {
        Show-Usage
        return 0
    }

    # 命令行模式
    if ($IsCli) {
        $key = Resolve-Target $Target
        if (-not $key) { Write-Err "未知目标: $Target"; return 1 }
        return (Invoke-Target $key)
    }

    # 交互式菜单模式
    while ($true) {
        Show-Menu
        $choice = Read-Host 'Select [0-6/H]'
        if ([string]::IsNullOrWhiteSpace($choice)) { continue }

        switch -Regex ($choice.Trim().ToLowerInvariant()) {
            '^0$'        { return 0 }
            '^(h|help)$' { Show-Usage; Write-Host ''; Read-Host '按回车返回菜单' | Out-Null; continue }
            default {
                $key = Resolve-Target $choice
                if (-not $key) {
                    Write-Host ''
                    Write-Err "无效选项: $choice"
                    Write-Host ''
                    Read-Host '按回车返回菜单' | Out-Null
                    continue
                }
                Invoke-Target $key | Out-Null
                Write-Host ''
                Read-Host '按回车返回菜单' | Out-Null
            }
        }
    }
}

try {
    $exit = Invoke-Main
    exit $exit
} finally {
    Pop-Location -ErrorAction SilentlyContinue
}
