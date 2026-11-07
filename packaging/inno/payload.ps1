<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>

function Resolve-RwGameRoot {
    param([string]$RepoRoot, [string]$GameRoot)
    # build/build.ps1 的同级原版 ZIP 优先于从其他开发机复制来的环境和本机配置。
    if (-not $GameRoot) {
        $adjacentArchive = Join-Path $RepoRoot 'build/Rusted-Warfare-win.zip'
        if (Test-Path -LiteralPath $adjacentArchive -PathType Leaf) { $GameRoot = $adjacentArchive }
    }
    if (-not $GameRoot) { $GameRoot = $env:RW_GAME_ARCHIVE }
    if (-not $GameRoot) { $GameRoot = $env:RW_GAME_ROOT }
    foreach ($setting in @('game-archive.local.txt', 'game-root.local.txt')) {
        if ($GameRoot) { break }
        $localFile = Join-Path $RepoRoot "packaging/$setting"
        if (Test-Path -LiteralPath $localFile -PathType Leaf) {
            $GameRoot = Get-Content -LiteralPath $localFile -Encoding UTF8 |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -First 1
        }
    }
    if (-not $GameRoot) { throw 'Put Rusted-Warfare-win.zip next to build/build.ps1, or set -GameArchive / RW_GAME_ARCHIVE.' }
    $GameRoot = $GameRoot.Trim().Trim('"')
    if (-not [IO.Path]::IsPathRooted($GameRoot)) { $GameRoot = Join-Path $RepoRoot $GameRoot }
    return [IO.Path]::GetFullPath($GameRoot.Trim().Trim('"'))
}

function Test-RwPayloadFile {
    param([string]$RelativePath)
    $parts = $RelativePath.Replace('\', '/').Split('/')
    $name = $parts[-1]
    # 原版 SDK jar 含 java.* 占位类，仍排除；桌面所需缺失类型由 app/rwjs-android-compat.jar 补齐。
    if ($parts[0] -eq 'libs' -and $name -in @('android.jar', 'android-game-lib.jar')) { return $false }
    if ($name -match '^(io\.github\.rwpp\.|RWPP|RWJS|launcher\.)' -or
        $name -match '\.(toml|bak|log|hprof)$' -or $name -eq 'preferences.ini') { return $false }
    # 只收集游戏运行资源，原版 exe、启动脚本、JVM 及用户数据不属于 RWJS 安装包。
    if ($parts.Length -eq 1) { return $name -eq 'game-lib.jar' -or $name -eq 'steam_appid.txt' -or $name -match '\.(dll|so(?:\.\d+)*|dylib)$' }
    return $parts[0] -in @('assets', 'font', 'libs', 'res')
}

function Copy-RwGamePayload {
    param([string]$RepoRoot, [string]$GameRoot)
    if (Test-Path -LiteralPath $GameRoot -PathType Leaf) {
        return Copy-RwArchivePayload -RepoRoot $RepoRoot -GameArchive $GameRoot
    }
    if (-not (Test-Path -LiteralPath (Join-Path $GameRoot 'game-lib.jar') -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $GameRoot 'assets') -PathType Container)) {
        throw "Game root must contain game-lib.jar and assets/: $GameRoot. Set RW_GAME_ROOT or packaging/game-root.local.txt."
    }
    $repoFull = [IO.Path]::GetFullPath($RepoRoot)
    $stagingRoot = [IO.Path]::GetFullPath((Join-Path $repoFull 'build/tmp/game-payload'))
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $repoFull 'build'))
    if (-not $stagingRoot.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe staging path: $stagingRoot"
    }
    $gameFull = [IO.Path]::GetFullPath($GameRoot).TrimEnd('\', '/')
    if ($gameFull.Equals($stagingRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $gameFull.StartsWith($stagingRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $stagingRoot.StartsWith($gameFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Game source and staging directory must not overlap.'
    }
    if (Test-Path -LiteralPath $stagingRoot) { Remove-Item -LiteralPath $stagingRoot -Recurse -Force }
    New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
    $copied = 0
    $bytes = 0L
    # 避免遍历用户模组/存档，且不跟随游戏目录中的符号链接或 junction。
    $pending = [System.Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($gameFull)
    while ($pending.Count -gt 0) {
        $directory = $pending.Dequeue()
        foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            if ($entry.PSIsContainer) {
                if ($directory -ne $gameFull -or $entry.Name -in @('assets', 'font', 'libs', 'res')) {
                    $pending.Enqueue($entry.FullName)
                }
                continue
            }
            $relative = $entry.FullName.Substring($gameFull.Length + 1)
            if (-not (Test-RwPayloadFile $relative)) { continue }
            $destination = Join-Path $stagingRoot $relative
            New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $entry.FullName -Destination $destination -Force
            $copied++
            $bytes += $entry.Length
        }
    }
    Write-Host ('Game payload: {0} files, {1:N1} MiB from {2}' -f $copied, ($bytes / 1MB), $GameRoot)
    return $stagingRoot
}

# 直接按白名单从 ZIP 提取，不展开原版 JVM、存档或用户模组。
function Get-RwArchivePlan {
    param($Archive)
    $entries = @{}
    foreach ($entry in $Archive.Entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ($name.StartsWith('/') -or ($name.Split('/') | Where-Object { $_ -in @('.', '..') -or $_.Contains(':') })) {
            throw "Unsafe archive path: $name"
        }
        if ((($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
            throw "Archive symlink is not allowed: $name"
        }
        if ($name.EndsWith('/')) { continue }
        if ($entries.ContainsKey($name)) { throw "Duplicate or case-conflicting archive path: $name" }
        $entries[$name] = $entry
    }
    $roots = @($entries.Keys | Where-Object { $_ -eq 'game-lib.jar' -or $_.EndsWith('/game-lib.jar') } |
        ForEach-Object {
            $prefix = $_.Substring(0, $_.Length - 'game-lib.jar'.Length)
            if (@($entries.Keys | Where-Object { $_.StartsWith($prefix + 'assets/', [StringComparison]::Ordinal) }).Count -gt 0) { $prefix }
        })
    if ($roots.Count -ne 1) { throw 'ZIP must contain exactly one game root with game-lib.jar and non-empty assets/.' }
    $prefix = $roots[0]
    $plan = @{}
    foreach ($name in $entries.Keys) {
        if (-not $name.StartsWith($prefix, [StringComparison]::Ordinal)) { continue }
        $relative = $name.Substring($prefix.Length)
        if (Test-RwPayloadFile $relative) { $plan[$relative] = $entries[$name] }
    }
    return $plan
}

function Assert-RwGameSource {
    param([string]$Source, [switch]$Windows)
    if (Test-Path -LiteralPath $Source -PathType Leaf) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($Source)
        try {
            $plan = Get-RwArchivePlan $archive
            if ($Windows -and -not $plan.ContainsKey('lwjgl64.dll')) {
                throw 'Windows installer needs a Windows x64 original ZIP (missing lwjgl64.dll); a Linux ZIP cannot supply Windows native libraries.'
            }
        } finally { $archive.Dispose() }
    } elseif (-not (Test-Path -LiteralPath (Join-Path $Source 'game-lib.jar') -PathType Leaf) -or
              -not (Test-Path -LiteralPath (Join-Path $Source 'assets') -PathType Container)) {
        throw "Game source must be an original ZIP or a directory with game-lib.jar and assets/: $Source"
    } elseif ($Windows -and -not (Test-Path -LiteralPath (Join-Path $Source 'lwjgl64.dll') -PathType Leaf)) {
        throw "Windows original resources are missing lwjgl64.dll: $Source"
    }
}

function Copy-RwArchivePayload {
    param([string]$RepoRoot, [string]$GameArchive)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $stagingRoot = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'build/tmp/game-payload'))
    $source = [IO.Path]::GetFullPath($GameArchive)
    if ($source.StartsWith($stagingRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Game archive must not be inside its staging directory.'
    }
    if ((Test-Path -LiteralPath $stagingRoot) -and
        ((Get-Item -LiteralPath $stagingRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Game staging directory must not be a symlink or junction.'
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($source)
    try {
        $plan = Get-RwArchivePlan $archive
        # 校验后才清理旧暂存；解压内容永远不会写回原版包。
        if (Test-Path -LiteralPath $stagingRoot) { Remove-Item -LiteralPath $stagingRoot -Recurse -Force }
        New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
        foreach ($relative in $plan.Keys) {
            $dest = Join-Path $stagingRoot $relative
            New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
            $inputStream = $plan[$relative].Open()
            try {
                $outputStream = [IO.File]::Create($dest)
                try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
            } finally { $inputStream.Dispose() }
        }
        Write-Host "Game ZIP payload: $($plan.Count) files from $source"
    } finally { $archive.Dispose() }
    return $stagingRoot
}
