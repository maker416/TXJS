<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>

function Update-RwProjectVersion([string]$RootDir) {
    $path = Join-Path $RootDir 'build.gradle.kts'
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    # 保留原有 BOM、换行、注释和其余 Gradle 配置，只替换版本字符串。
    $content = $utf8.GetString([IO.File]::ReadAllBytes($path))
    $assignments = [regex]::Matches($content, '(?m)^(?:\uFEFF)?[ \t]*version[ \t]*=[ \t]*"([^"\r\n]*)"')
    if ($assignments.Count -ne 1) {
        throw 'build.gradle.kts 必须包含唯一的 version = "x.y.z"。'
    }
    $value = $assignments[0].Groups[1]
    $parts = [regex]::Match($value.Value, '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')
    if (-not $parts.Success) { throw "客户端版本号必须为 x.y.z: $($value.Value)" }
    $patch = [long]::Parse($parts.Groups[3].Value)
    if ($patch -eq [long]::MaxValue) { throw '客户端补丁版本号已超出支持范围。' }
    $version = '{0}.{1}.{2}' -f $parts.Groups[1].Value, $parts.Groups[2].Value, ($patch + 1)
    $updated = $content.Substring(0, $value.Index) + $version + $content.Substring($value.Index + $value.Length)
    $temporary = "$path.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporary, $updated, $utf8)
        [IO.File]::Replace($temporary, $path, [NullString]::Value)
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
    return [pscustomobject]@{ Previous = $value.Value; Current = $version }
}
