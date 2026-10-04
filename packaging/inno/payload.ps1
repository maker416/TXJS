<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>

function Resolve-RwGameRoot {
    param([string]$RepoRoot, [string]$GameRoot)
    if (-not $GameRoot) { $GameRoot = $env:RW_GAME_ROOT }
    if (-not $GameRoot) {
        $localFile = Join-Path $RepoRoot 'packaging/game-root.local.txt'
        if (Test-Path -LiteralPath $localFile -PathType Leaf) {
            $GameRoot = Get-Content -LiteralPath $localFile |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -First 1
        }
    }
    if (-not $GameRoot) { $GameRoot = 'D:\APP\Steam\steamapps\common\Rusted Warfare' }
    return [IO.Path]::GetFullPath($GameRoot.Trim().Trim('"'))
}

function Test-RwPayloadFile {
    param([string]$RelativePath)
    $parts = $RelativePath.Replace('\', '/').Split('/')
    $name = $parts[-1]
    if ($name -match '^(io\.github\.rwpp\.|RWPP|RWJS|launcher\.)' -or
        $name -match '\.(toml|bak|log|hprof)$' -or $name -eq 'preferences.ini') { return $false }
    # 只收集游戏运行资源，原版 exe、启动脚本、JVM 及用户数据不属于 RWJS 安装包。
    if ($parts.Length -eq 1) { return $name -eq 'game-lib.jar' -or $name -eq 'steam_appid.txt' -or $name -match '\.(dll|so|dylib)$' }
    return $parts[0] -in @('assets', 'font', 'libs', 'res')
}

function Copy-RwGamePayload {
    param([string]$RepoRoot, [string]$GameRoot)
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
