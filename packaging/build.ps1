<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
<#
.SYNOPSIS
    RWJS 构建工具（PowerShell 版）。

.DESCRIPTION
    在项目根目录调用 gradlew 执行构建，并在成功后把产物收集到 build\artifacts\<子目录> 下。
    支持交互式菜单与命令行参数两种方式。

.PARAMETER Target
    构建目标。可选：desktop / installer / test / android-debug / android-release / clean
    省略时进入交互式菜单。

.PARAMETER Channel
    安装包渠道标识（字母/数字/下划线/连字符，1-64 字符），打包时通过
    -PonlineChannel 注入 BuildConfig，随在线上报传给统计服务器。
    省略时默认为 official；交互式菜单中每次构建前会询问。

.EXAMPLE
    .\build.ps1                 # 打开交互式菜单
    .\build.ps1 desktop         # 构建桌面端 fat jar
    .\build.ps1 installer             # 构建 Windows Inno Setup 安装包
    .\build.ps1 clean           # 受保护的 Gradle clean
    .\build.ps1 android-release -Channel tt_ad   # 构建 tt_ad 渠道的 Release APK
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Target,

    [string]$Channel,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

# 统一控制台输出为 UTF-8，保证中文正常显示
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ---------------------------------------------------------------------------
# 路径与全局配置
# ---------------------------------------------------------------------------
$Script:BuildDir     = Join-Path $PSScriptRoot '../build'
$Script:RootDir      = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Script:ArtifactsDir = Join-Path $BuildDir 'artifacts'
$Script:GradlewBat   = Join-Path $RootDir 'gradlew.bat'
$Script:GradleArgs   = @('--no-daemon', '--parallel', '--build-cache', '--configure-on-demand')
$Script:IsCli        = [bool]$PSBoundParameters.ContainsKey('Target') -and -not [string]::IsNullOrWhiteSpace($Target)
$Script:Version      = 'unknown'
$Script:Channel      = 'official'
$Script:ChannelPattern = '^[A-Za-z0-9_-]{1,64}$'
# 这些目标不参与渠道注入（不打渠道包）
$Script:NoChannelTargets = @('test', 'clean')

# ---------------------------------------------------------------------------
# 目标定义表
# ---------------------------------------------------------------------------
$Script:Targets = [ordered]@{
    'desktop' = @{
        Name          = 'Desktop fat jar'
        Tasks         = @(':rwpp-desktop:packageReleaseUberJarForCurrentOS')
        Subdir        = 'desktop-jar'
        Collect       = 'desktop'
        Aliases       = @('1', 'jar', 'fatjar')
    }
    'installer' = @{
        Name          = 'RWJS Windows installer (Inno Setup)'
        Tasks         = @(':rwpp-desktop:packageInnoDistribution')
        Subdir        = 'installer'
        Collect       = 'installer'
        Aliases       = @('2', 'inno', 'setup')
    }
    'test' = @{
        Name          = 'Tests'
        Tasks         = @(':rwpp-core:test', ':rwpp-core-api:test')
        Subdir        = 'test-reports'
        Collect       = 'test'
        Aliases       = @('3', 'tests')
    }
    'android-debug' = @{
        Name          = 'Android Debug APK'
        Tasks         = @(':rwpp-android:assembleDebug')
        Subdir        = 'android-debug'
        Collect       = 'android-debug'
        Aliases       = @('4', 'debug', 'apk-debug')
    }
    'android-release' = @{
        Name          = 'Android Release APK'
        Tasks         = @(':rwpp-android:assembleRelease')
        Subdir        = 'android-release'
        Collect       = 'android-release'
        Aliases       = @('5', 'release', 'apk-release')
    }
    'clean' = @{
        Name          = 'Safe clean'
        Tasks         = @('clean')
        Subdir        = $null
        Collect       = 'none'
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
        Write-Err "未找到 gradlew.bat，请把脚本放在 TXJS\packaging\ 下。"; return $false
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
# 渠道（channel）处理
# ---------------------------------------------------------------------------
function Test-ChannelName ([string]$Name) {
    return $Name -match $Script:ChannelPattern
}

# 确定本次构建使用的渠道：CLI 已通过 -Channel 指定时直接使用；
# 交互模式每次构建前询问，回车沿用上次值（默认为 official）。
function Read-Channel ([string]$Key) {
    if ($NoChannelTargets -contains $Key) { return $true }

    if (-not $IsCli) {
        $input2 = Read-Host "渠道标识 [$($Script:Channel)]"
        if (-not [string]::IsNullOrWhiteSpace($input2)) {
            $Script:Channel = $input2.Trim()
        }
    }
    if (-not (Test-ChannelName $Script:Channel)) {
        Write-Err "渠道标识只能包含字母、数字、下划线、连字符（1-64 字符）: $($Script:Channel)"
        return $false
    }
    return $true
}

# 渠道包产物按渠道分目录存放，避免不同渠道互相覆盖。
function Get-OutputSubdir ([string]$Key) {
    $subdir = $Targets[$Key].Subdir
    if ([string]::IsNullOrWhiteSpace($subdir)) { return $null }
    if ($NoChannelTargets -contains $Key) { return $subdir }
    return "$subdir-$($Script:Channel)"
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
    Write-Host '  RWJS Build Tool (PowerShell)'
    Write-Host "  Version: $Version  |  Root: $RootDir"
    Write-Host "  Channel: $($Script:Channel)  |  Mode: 默认增量构建，产物收集到 build\artifacts\"
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
    Write-Host '  [2] RWJS Inno Setup installer (需要 Inno Setup 6.5+ 和原版游戏资源)'
    Write-Host '      Task:   :rwpp-desktop:packageInnoDistribution'
    Write-Host '      Game:   RW_GAME_ROOT 或 packaging\game-root.local.txt'
    Write-Host '      Output: build\artifacts\installer\'
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
    Write-Host '  build.ps1 installer       构建 Windows Inno Setup 安装包'
    Write-Host '  build.ps1 test            运行核心测试'
    Write-Host '  build.ps1 android-debug   构建 Android Debug APK'
    Write-Host '  build.ps1 android-release 构建 Android Release APK'
    Write-Host '  build.ps1 clean           运行受保护的 Gradle clean'
    Write-Host ''
    Write-Host 'Channel（打包渠道，test/clean 无需指定）:'
    Write-Host '  build.ps1 android-release -Channel tt_ad   构建 tt_ad 渠道包'
    Write-Host '  - 渠道标识只允许字母、数字、下划线、连字符（1-64 字符），默认 official'
    Write-Host '  - 交互式菜单中每次构建前会询问渠道，回车沿用上次值'
    Write-Host '  - 不同渠道产物分别放在 build\artifacts\<目标>-<渠道>\ 下，互不覆盖'
    Write-Host '  - 渠道随在线上报传给统计服务器，用于按渠道统计新增/留存'
    Write-Host ''
    Write-Host 'Aliases:'
    Write-Host '  desktop, jar, fatjar'
    Write-Host '  installer, inno, setup'
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

    if ($Key -eq 'installer') {
        $gameRoot = Resolve-RwGameRoot
        $gameLib = Join-Path $gameRoot 'game-lib.jar'
        if (-not (Test-Path -LiteralPath $gameLib -PathType Leaf)) {
            Write-Err "一体包需要原版游戏目录（缺少 game-lib.jar）: $gameRoot"
            Write-Info '请设置环境变量 RW_GAME_ROOT，或在 packaging\game-root.local.txt 写入路径。'
            return $false
        }
        Write-Info "一体包游戏根目录: $gameRoot"
        $env:RW_GAME_ROOT = $gameRoot
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

function Resolve-RwGameRoot {
    $fromEnv = [Environment]::GetEnvironmentVariable('RW_GAME_ROOT')
    if (-not [string]::IsNullOrWhiteSpace($fromEnv)) {
        return [System.IO.Path]::GetFullPath($fromEnv.Trim().Trim('"'))
    }
    $localFile = Join-Path $RootDir 'packaging\game-root.local.txt'
    if (Test-Path -LiteralPath $localFile -PathType Leaf) {
        $line = Get-Content -LiteralPath $localFile |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and -not $_.StartsWith('#') } |
            Select-Object -First 1
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            return [System.IO.Path]::GetFullPath($line.Trim().Trim('"'))
        }
    }
    return [System.IO.Path]::GetFullPath('D:\APP\Steam\steamapps\common\Rusted Warfare')
}

# ---------------------------------------------------------------------------
# 执行 Gradle
# ---------------------------------------------------------------------------
function Invoke-Gradle ([string[]]$Tasks) {
    $allArgs = $GradleArgs + "-PonlineChannel=$($Script:Channel)" + $Tasks
    Write-Host "[Gradle] $GradlewBat $($allArgs -join ' ')" -ForegroundColor DarkGray
    Write-Host ''
    # 通过 Out-Host 让 Gradle 输出直接写入终端，避免它混入函数返回值（success stream），从而保证退出码判断正确
    & $GradlewBat @allArgs | Out-Host
    return $LASTEXITCODE
}

function Invoke-Target ([string]$Key) {
    $def = $Targets[$Key]

    if (-not (Read-Channel $Key)) { return 1 }
    if (-not (Test-Preflight $Key)) { return 1 }

    $outputSubdir = Get-OutputSubdir $Key
    Write-Host ''
    Write-Host '------------------------------------------------------------'
    Write-Host "  Target:  $($def.Name)"
    Write-Host "  Version: $Version"
    if ($NoChannelTargets -notcontains $Key) { Write-Host "  Channel: $($Script:Channel)" }
    Write-Host "  Tasks:   $($def.Tasks -join ' ')"
    if ($outputSubdir) { Write-Host "  Output:  build\artifacts\$outputSubdir\" }
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
            try {
                Invoke-CollectArtifacts $Key
            } catch {
                Write-Err "产物收集失败: $($_.Exception.Message)"
                return 1
            }
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
    $artifactRoot = [IO.Path]::GetFullPath($ArtifactsDir)
    $dest = [IO.Path]::GetFullPath((Join-Path $artifactRoot $Subdir))
    if (-not $dest.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe artifacts directory: $dest"
    }
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
        'RWJS Build Info'
        '===================='
        "Version: $Version"
        "Channel: $($Script:Channel)"
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

    switch ($def.Collect) {
            'desktop'         { Copy-DesktopJar  $Key }
            'installer'       { Copy-InstallerFiles $Key }
            'test'            { Copy-TestReports $Key }
            'android-debug'   { Copy-Apk $Key 'rwpp-android\build\outputs\apk\debug'   'rwpp-android-debug' }
            'android-release' { Copy-Apk $Key 'rwpp-android\build\outputs\apk\release' 'rwpp-android-release' }
            default           { }
    }
}

function Copy-DesktopJar ([string]$Key) {
    $dest = Reset-Destination (Get-OutputSubdir $Key)
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

function Copy-InstallerFiles ([string]$Key) {
    $setupExe = Join-Path $BuildDir 'installer/RWJS-Setup.exe'
    if (-not (Test-Path -LiteralPath $setupExe -PathType Leaf)) {
        throw "RWJS installer was not generated: $setupExe"
    }
    $dest = Reset-Destination (Get-OutputSubdir $Key)
    Copy-Item -LiteralPath $setupExe -Destination (Join-Path $dest "RWJS-Setup-$Version.exe") -Force
    $destSetupExe = Join-Path $dest 'RWJS-Setup.exe'
    Copy-Item -LiteralPath $setupExe -Destination $destSetupExe -Force
    Split-SetupPackage $destSetupExe $dest
    Write-BuildInfo $dest $Key
    Write-Step "Inno Setup 安装包已复制到: $dest"
    Get-ChildItem -LiteralPath $dest -Filter 'RWJS*' | ForEach-Object { Write-Host "  $($_.Name)" }
}
# 把安装包打成 zip 后按字节分卷（每卷 <= 95MB），命名沿用 7-Zip/WinRAR 分卷约定
# （RWJS-Setup.zip.001/.002…），用户可直接用压缩软件打开 .001 解压；
# 更新客户端按顺序拼接即为合法 zip，可流式解出 exe。
# 同时生成 RWJS-Setup.zip.sha256（合并 zip 的 SHA-256），供客户端下载后强校验。
function Split-SetupPackage ([string]$SourceExe, [string]$Dest) {
    $partSize = 95MB
    $zipPath  = Join-Path $Dest 'RWJS-Setup.zip'

    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Get-ChildItem -LiteralPath $Dest -Filter 'RWJS-Setup.zip.*' -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue

    Write-Step '正在生成 Gitee 分卷包（zip 分卷，每卷 95MB）...'
    Compress-Archive -LiteralPath $SourceExe -DestinationPath $zipPath -CompressionLevel NoCompression -Force

    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$zipPath.sha256" -Value "$hash  RWJS-Setup.zip" -Encoding ASCII

    $fs = [System.IO.File]::OpenRead($zipPath)
    try {
        $partIndex = 1
        $buffer = New-Object byte[] (4MB)
        while ($true) {
            $partPath = '{0}.{1:D3}' -f $zipPath, $partIndex
            $partWritten = 0L
            $out = [System.IO.File]::Create($partPath)
            try {
                while ($partWritten -lt $partSize) {
                    $toRead = [int][Math]::Min($buffer.Length, $partSize - $partWritten)
                    $read = $fs.Read($buffer, 0, $toRead)
                    if ($read -le 0) { break }
                    $out.Write($buffer, 0, $read)
                    $partWritten += $read
                }
            } finally { $out.Dispose() }
            # zip 大小恰好为分卷整数倍时会产生一个空卷，删除即可
            if ($partWritten -eq 0) {
                Remove-Item -LiteralPath $partPath -Force
                break
            }
            $partIndex++
            if ($partWritten -lt $partSize) { break }  # 最后一卷
        }
    } finally { $fs.Dispose() }
    Remove-Item -LiteralPath $zipPath -Force
    Write-Step ("分卷包已生成: RWJS-Setup.zip.001 ~ RWJS-Setup.zip.{0:D3} + RWJS-Setup.zip.sha256" -f ($partIndex - 1))
}

function Copy-TestReports ([string]$Key) {
    $dest = Reset-Destination (Get-OutputSubdir $Key)
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
    $dest = Reset-Destination (Get-OutputSubdir $Key)
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
    $scriptSelf = Join-Path $PSScriptRoot 'build.ps1'
    if (Test-Path -LiteralPath $scriptSelf) {
        Write-Host '[PROTECT] packaging\build.ps1 仍然存在。' -ForegroundColor Green
    } else {
        Write-Err 'packaging\build.ps1 不存在，请检查仓库文件。'
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

    if (-not [string]::IsNullOrWhiteSpace($Channel)) {
        $Script:Channel = $Channel.Trim()
        if (-not (Test-ChannelName $Script:Channel)) {
            Write-Err "渠道标识只能包含字母、数字、下划线、连字符（1-64 字符）: $($Script:Channel)"
            return 1
        }
    }

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
