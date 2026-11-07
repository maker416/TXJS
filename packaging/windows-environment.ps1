<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>

# 只配置当前打包进程及其子进程，不把开发机路径写入仓库或用户环境变量。
function Get-RwToolProbe {
    param([string]$Executable, [string]$Arguments)
    $process = [Diagnostics.Process]::new()
    try {
        $process.StartInfo = [Diagnostics.ProcessStartInfo]::new()
        $process.StartInfo.FileName = $Executable
        $process.StartInfo.Arguments = $Arguments
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.StartInfo.RedirectStandardInput = $true
        if (-not $process.Start()) { return $null }
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill()
            $process.WaitForExit()
            return $null
        }
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.Result + "`n" + $stderr.Result }
    } catch { return $null } finally { $process.Dispose() }
    return $null
}

function Get-RwJavaMajorVersion {
    param([string]$JavaExecutable)
    $probe = Get-RwToolProbe $JavaExecutable '--version'
    if ($probe -and $probe.ExitCode -eq 0 -and $probe.Output -match '(?m)^(?:openjdk|java)\s+(\d+)(?:[.\s+-]|$)') {
        return [int]$Matches[1]
    }
    return $null
}

function Get-RwWindowsToolsRoot {
    if (-not $env:LOCALAPPDATA) { throw '无法确定当前用户的 LOCALAPPDATA，不能自动配置 Windows 打包工具。' }
    return Join-Path $env:LOCALAPPDATA 'RWJS/BuildTools'
}

function Get-RwJdkCandidates {
    $candidates = [Collections.Generic.List[string]]::new()
    foreach ($value in @($env:JAVA_HOME, $env:JAVA_HOME_21_X64, $env:JDK_HOME)) {
        if ($value) { $candidates.Add($value.Trim().Trim('"')) }
    }
    foreach ($command in @(Get-Command java.exe -All -CommandType Application -ErrorAction SilentlyContinue)) {
        $candidates.Add((Split-Path (Split-Path $command.Source -Parent) -Parent))
    }
    foreach ($scope in @('User', 'Machine')) {
        $value = [Environment]::GetEnvironmentVariable('JAVA_HOME', $scope)
        if ($value) { $candidates.Add($value.Trim().Trim('"')) }
    }
    if (Get-PSProvider Registry -ErrorAction SilentlyContinue) {
        foreach ($key in @('HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\Eclipse Adoptium\JDK',
                'HKCU:\SOFTWARE\Eclipse Adoptium\JDK', 'HKLM:\SOFTWARE\Microsoft\JDK',
                'HKLM:\SOFTWARE\Azul Systems\Zulu')) {
            foreach ($entry in @(Get-ChildItem -LiteralPath $key -Recurse -ErrorAction SilentlyContinue)) {
                $properties = Get-ItemProperty -LiteralPath $entry.PSPath -ErrorAction SilentlyContinue
                foreach ($name in @('JavaHome', 'Path', 'InstallationPath')) {
                    $value = $properties.$name
                    if ($value -is [string] -and $value) { $candidates.Add($value) }
                }
            }
        }
    }
    $bases = @($env:ProgramW6432, $env:ProgramFiles, ${env:ProgramFiles(x86)})
    if ($env:LOCALAPPDATA) { $bases += (Join-Path $env:LOCALAPPDATA 'Programs') }
    foreach ($base in $bases) {
        if (-not $base) { continue }
        foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'BellSoft')) {
            $folder = Join-Path $base $vendor
            foreach ($directory in @(Get-ChildItem -LiteralPath $folder -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
                $candidates.Add($directory.FullName)
            }
        }
    }
    if ($env:USERPROFILE) {
        foreach ($directory in @(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue)) {
            $candidates.Add($directory.FullName)
        }
    }
    if ($env:LOCALAPPDATA) {
        foreach ($directory in @(Get-ChildItem -LiteralPath (Get-RwWindowsToolsRoot) -Filter 'jdk-*' -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
            $candidates.Add($directory.FullName)
        }
    }
    return @($candidates | Select-Object -Unique)
}

function Find-RwJdk21 {
    param([string[]]$Candidates)
    if (-not $PSBoundParameters.ContainsKey('Candidates')) { $Candidates = @(Get-RwJdkCandidates) }
    foreach ($homePath in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($homePath)) { continue }
        $homePath = $homePath.Trim().Trim('"')
        $required = @('release', 'bin/java.exe', 'bin/javac.exe', 'bin/jlink.exe', 'bin/jpackage.exe', 'jmods/java.base.jmod')
        if (@($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $homePath $_) -PathType Leaf) }).Count) { continue }
        $release = Get-Content -LiteralPath (Join-Path $homePath 'release') -Raw
        if ($release -notmatch '(?m)^JAVA_VERSION="21(?:[.\-+][^"]*)?"') { continue }
        if ($release -match '(?m)^OS_ARCH="([^"]+)"' -and $Matches[1] -notin @('amd64', 'x86_64', 'x64')) { continue }
        if ((Get-RwJavaMajorVersion (Join-Path $homePath 'bin/java.exe')) -eq 21) {
            return [IO.Path]::GetFullPath($homePath)
        }
    }
    return $null
}

function Get-RwInnoRegistryEntries {
    if (-not (Get-PSProvider Registry -ErrorAction SilentlyContinue)) { return }
    # 显式读取两种注册表视图，兼容从 32 位 PowerShell 启动及自定义安装目录。
    foreach ($hive in @([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryHive]::LocalMachine)) {
        foreach ($view in @([Microsoft.Win32.RegistryView]::Registry32, [Microsoft.Win32.RegistryView]::Registry64)) {
            $baseKey = $null
            try {
                $baseKey = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
                foreach ($name in @('Inno Setup 6_is1', 'Inno Setup 7_is1')) {
                    $key = $baseKey.OpenSubKey("SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$name")
                    if (-not $key) { continue }
                    try {
                        [pscustomobject]@{
                            InstallLocation = $key.GetValue('InstallLocation')
                            AppPath = $key.GetValue('Inno Setup: App Path')
                        }
                    } finally { $key.Dispose() }
                }
            } catch { Write-Verbose "无法读取 Inno Setup 注册表路径：$_" } finally {
                if ($baseKey) { $baseKey.Dispose() }
            }
        }
    }
}

function Get-RwInnoCandidates {
    if ($env:INNO_SETUP_COMPILER) { $env:INNO_SETUP_COMPILER.Trim().Trim('"') }
    $command = Get-Command ISCC.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { $command.Source }
    foreach ($entry in @(Get-RwInnoRegistryEntries)) {
        foreach ($directory in @($entry.InstallLocation, $entry.AppPath)) {
            if ($directory) { Join-Path ($directory.Trim().Trim('"')) 'ISCC.exe' }
        }
    }
    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramW6432, $env:ProgramFiles, $env:LOCALAPPDATA)) {
        if (-not $base) { continue }
        foreach ($relative in @('Inno Setup 6/ISCC.exe', 'Programs/Inno Setup 6/ISCC.exe',
                'Inno Setup 7/ISCC.exe', 'Programs/Inno Setup 7/ISCC.exe')) {
            Join-Path $base $relative
        }
    }
    if ($env:LOCALAPPDATA) {
        foreach ($directory in @(Get-ChildItem -LiteralPath (Get-RwWindowsToolsRoot) -Filter 'Inno Setup *' -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
            Join-Path $directory.FullName 'ISCC.exe'
        }
    }
}

function Get-RwInnoVersion {
    param([string]$Compiler)
    # ISCC.exe 的文件版本可能为 0.0.0.0，/? 也只显示主版本。
    # 从已关闭的空 stdin 解析脚本（预期报错），读取真实引擎版本；/O- 禁止输出安装包。
    $probe = Get-RwToolProbe $Compiler '/O- -'
    if ($probe -and $probe.Output -match '(?im)^Compiler engine version:\s*Inno Setup[^\r\n]*?\b(\d+\.\d+\.\d+(?:\.\d+)?)\b') {
        return [version]$Matches[1]
    }
    return $null
}

function Find-RwInnoCompiler {
    param([string[]]$Candidates)
    if (-not $PSBoundParameters.ContainsKey('Candidates')) { $Candidates = @(Get-RwInnoCandidates | Select-Object -Unique) }
    foreach ($candidate in $Candidates) {
        if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $version = Get-RwInnoVersion $candidate
        if ($version -and $version -ge [version]'6.5.0') { return [IO.Path]::GetFullPath($candidate) }
    }
    return $null
}

function Get-RwWingetInstallArguments {
    param([ValidateSet('EclipseAdoptium.Temurin.21.JDK', 'JRSoftware.InnoSetup')][string]$PackageId)
    $installArgs = @('install', '--id', $PackageId, '--exact', '--source', 'winget',
        '--silent', '--disable-interactivity', '--accept-package-agreements', '--accept-source-agreements')
    # Inno 6 的编译器可能是 x86 程序，仍能生成 x64 安装包；不要强制它选择 x64 安装器。
    if ($PackageId -eq 'EclipseAdoptium.Temurin.21.JDK') { $installArgs += @('--architecture', 'x64') }
    return $installArgs
}

function Find-RwWinget {
    $command = Get-Command winget.exe -CommandType Application -ErrorAction SilentlyContinue
    $wingetPath = if ($command) { $command.Source } else { $null }
    if (-not $wingetPath -and $env:LOCALAPPDATA) {
        $candidate = Join-Path $env:LOCALAPPDATA 'Microsoft/WindowsApps/winget.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $wingetPath = $candidate }
    }
    return $wingetPath
}

function Invoke-RwWingetInstall {
    param([string]$WingetPath, [string]$PackageId)
    $PSNativeCommandUseErrorActionPreference = $false
    $installArgs = @(Get-RwWingetInstallArguments $PackageId)
    & $WingetPath @installArgs | Out-Host
    return $LASTEXITCODE
}

function Test-RwWindowsPackageInstalled {
    param([string]$PackageId)
    if ($PackageId -eq 'EclipseAdoptium.Temurin.21.JDK') { return [bool](Find-RwJdk21) }
    return [bool](Find-RwInnoCompiler)
}

function Save-RwWindowsDownload {
    param([string]$Uri, [string]$Destination, [string]$Sha256)
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) { return }
    }
    New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null
    $partial = $Destination + '.' + [Guid]::NewGuid().ToString('N') + '.partial'
    $tls = [Net.ServicePointManager]::SecurityProtocol
    try {
        [Net.ServicePointManager]::SecurityProtocol = $tls -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $partial -ErrorAction Stop | Out-Null
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) {
            throw '官方工具下载的 SHA-256 校验失败，已停止安装。'
        }
        Move-Item -LiteralPath $partial -Destination $Destination -Force
    } finally {
        [Net.ServicePointManager]::SecurityProtocol = $tls
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    }
}

function Invoke-RwInnoSetup {
    param([string]$Installer, [string]$Directory, [string]$LogPath)
    # 官方安装器允许 /CURRENTUSER；便携模式避免修改已有 Inno 安装及文件关联。
    $setupArgs = @('/CURRENTUSER', '/PORTABLE=1', '/VERYSILENT', '/SUPPRESSMSGBOXES', '/SP-',
        '/NORESTART', '/NOICONS', '/TASKS=""', "/DIR=`"$Directory`"", "/LOG=`"$LogPath`"")
    $process = Start-Process -FilePath $Installer -ArgumentList $setupArgs -Wait -PassThru -ErrorAction Stop
    try { return $process.ExitCode } finally { $process.Dispose() }
}

function Install-RwWindowsPackageDirect {
    param([ValidateSet('EclipseAdoptium.Temurin.21.JDK', 'JRSoftware.InnoSetup')][string]$PackageId)
    $toolsRoot = Get-RwWindowsToolsRoot
    $downloads = Join-Path $toolsRoot 'downloads'
    New-Item -ItemType Directory -Path $downloads -Force | Out-Null
    $suffix = [Guid]::NewGuid().ToString('N')
    if ($PackageId -eq 'EclipseAdoptium.Temurin.21.JDK') {
        # 固定官方发布版本及其 SHA-256，不执行未经校验的下载文件。
        # https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9
        $archive = Join-Path $downloads 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip'
        Write-Host '[ENV] 正在从 Eclipse Adoptium 官方发布下载并校验 x64 JDK 21...'
        Save-RwWindowsDownload -Uri 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip' `
            -Destination $archive -Sha256 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9'
        $stage = Join-Path $toolsRoot ('.jdk-stage-' + $suffix)
        $destination = Join-Path $toolsRoot ('jdk-21.0.13-' + $suffix)
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [IO.Compression.ZipFile]::ExtractToDirectory($archive, $stage)
            $jdk = Find-RwJdk21 -Candidates @(Get-ChildItem -LiteralPath $stage -Directory | ForEach-Object { $_.FullName })
            if (-not $jdk) { throw '下载的 JDK 未通过完整性或 Java 21 运行检查。' }
            Move-Item -LiteralPath $jdk -Destination $destination
            $env:JAVA_HOME = $destination
        } finally {
            if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        }
    } else {
        # https://github.com/jrsoftware/issrc/releases/tag/is-6_7_4
        $installer = Join-Path $downloads 'innosetup-6.7.4.exe'
        Write-Host '[ENV] 正在从 Inno Setup 官方发布下载并校验编译器...'
        Save-RwWindowsDownload -Uri 'https://github.com/jrsoftware/issrc/releases/download/is-6_7_4/innosetup-6.7.4.exe' `
            -Destination $installer -Sha256 'a549929319f9120716677c685535ca4671e4b937b5fdc87e2585f32d8eb30e8c'
        $destination = Join-Path $toolsRoot ('Inno Setup 6.7.4-' + $suffix)
        $log = Join-Path $downloads ('inno-install-' + $suffix + '.log')
        $complete = $false
        try {
            $code = Invoke-RwInnoSetup -Installer $installer -Directory $destination -LogPath $log
            if ($code -ne 0) { throw "Inno Setup 安装失败或被取消，退出码 $code。日志：$log" }
            $compiler = Find-RwInnoCompiler -Candidates @((Join-Path $destination 'ISCC.exe'))
            if (-not $compiler) { throw "安装后 Inno Setup 编译器运行检查未通过。日志：$log" }
            $env:INNO_SETUP_COMPILER = $compiler
            $complete = $true
        } finally {
            if (-not $complete -and (Test-Path -LiteralPath $destination)) {
                Remove-Item -LiteralPath $destination -Recurse -Force
            }
        }
    }
}

function Install-RwWindowsPackage {
    param([ValidateSet('EclipseAdoptium.Temurin.21.JDK', 'JRSoftware.InnoSetup')][string]$PackageId)
    $wingetPath = Find-RwWinget
    if ($wingetPath) {
        Write-Host "[ENV] 正在通过 WinGet 安装 $PackageId（如有 UAC，请在 Windows 提示中确认）..."
        try { $installCode = Invoke-RwWingetInstall $wingetPath $PackageId } catch {
            $cause = $_.Exception
            while ($cause.InnerException) { $cause = $cause.InnerException }
            if (($cause -is [ComponentModel.Win32Exception] -and $cause.NativeErrorCode -eq 1223) -or
                    $cause -is [OperationCanceledException]) { throw }
            Write-Host "[ENV] WinGet 无法启动，改用官方发布配置：$($cause.Message)"
            Install-RwWindowsPackageDirect $PackageId
            return
        }
        $unsignedCode = [long]$installCode -band 0xffffffffL
        # 用户取消不能通过另一个安装器绕过；哈希错误同样停止。
        if ($unsignedCode -in @(1602L, 1223L, 0x800704c7L, 0x8a150005L, 0x8a15010cL, 0x8a150077L, 0x8a150011L)) {
            throw "WinGet 安装 $PackageId 失败或被取消，退出码 $installCode。"
        }
        if ($unsignedCode -eq 0x8a15002bL) {
            Write-Host '[ENV] WinGet 提示已安装且无需升级，正在重新检测工具...'
        }
        # 包管理器的登记结果不代表工具能运行；以本次进程的实际检测为准。
        if (Test-RwWindowsPackageInstalled $PackageId) { return }
        Write-Host "[ENV] WinGet 返回 $installCode，但仍未检测到可用工具，改用官方发布配置。"
    } else {
        Write-Host '[ENV] 未找到 WinGet，改用官方发布配置打包工具。'
    }
    Install-RwWindowsPackageDirect $PackageId
}

function Add-RwProcessPath {
    param([string]$Directory)
    $separator = [IO.Path]::PathSeparator
    $entries = @($env:PATH -split [regex]::Escape([string]$separator) | Where-Object { $_ -and $_ -ne $Directory })
    $env:PATH = (@($Directory) + $entries) -join $separator
}

function Initialize-RwWindowsBuildEnvironment {
    param([string]$Target, [switch]$AllowInstall)
    $jdk = Find-RwJdk21
    $needsInno = $Target -eq 'installer'
    $compiler = if ($needsInno) { Find-RwInnoCompiler } else { $null }
    if (-not $AllowInstall) {
        $missing = @()
        if (-not $jdk) { $missing += '缺少完整 x64 JDK 21。去掉 -Check / -NoInstall 可自动配置，或设置 JAVA_HOME。' }
        if ($needsInno -and -not $compiler) { $missing += '缺少可运行的 Inno Setup 6.5+。去掉 -Check / -NoInstall 可自动配置，或设置 INNO_SETUP_COMPILER。' }
        if ($missing.Count) { throw ($missing -join "`n") }
    }
    if (-not $jdk) {
        Install-RwWindowsPackage 'EclipseAdoptium.Temurin.21.JDK'
        $jdk = Find-RwJdk21
        if (-not $jdk) { throw '安装后仍未找到完整 JDK 21。请检查安装结果，或将 JAVA_HOME 指向 JDK 21 后重试。' }
    }
    if ($needsInno -and -not $compiler) {
        Install-RwWindowsPackage 'JRSoftware.InnoSetup'
        $compiler = Find-RwInnoCompiler
        if (-not $compiler) { throw '安装后仍未找到 Inno Setup 6.5+。请检查安装结果，或设置 INNO_SETUP_COMPILER 后重试。' }
    }
    $env:JAVA_HOME = $jdk
    Add-RwProcessPath (Join-Path $jdk 'bin')
    Write-Host "[ENV] JAVA_HOME = $jdk"
    if ($compiler) {
        $env:INNO_SETUP_COMPILER = $compiler
        Add-RwProcessPath (Split-Path $compiler -Parent)
        Write-Host "[ENV] Inno Setup = $compiler"
    }
    return [pscustomobject]@{ JavaHome = $jdk; InnoCompiler = $compiler }
}
