<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
function Write-RwClientUpdateFiles {
    param([string]$Source, [string]$Dest, [string]$IndexRoot,
        [ValidateSet('windows', 'android')][string]$Platform, [string]$Version)
    $uri = $null
    if (-not [Uri]::TryCreate($IndexRoot, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        $uri.AbsolutePath -cnotmatch '^/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*$') {
        throw 'updateApiUrl 的索引目录必须是以 / 结尾的 HTTPS 地址。'
    }
    if ($Version -cnotmatch '^\d+\.\d+\.\d+$') { throw 'Invalid client update version' }
    $sourceFile = Get-Item -LiteralPath $Source
    if ($sourceFile.PSIsContainer -or $sourceFile.Length -le 0) { throw 'Client update installer is empty or missing' }

    $name = if ($Platform -eq 'windows') { "RWJS-Setup-$Version.exe" } else { "RWJS-Android-$Version.apk" }
    $uploadDir = Join-Path $Dest ('upneed' + $uri.AbsolutePath + $Platform)
    $versionDir = Join-Path $uploadDir $Version
    New-Item -ItemType Directory -Path $versionDir -Force | Out-Null
    $installer = Join-Path $versionDir $name
    Copy-Item -LiteralPath $sourceFile.FullName -Destination $installer
    $installerSize = (Get-Item -LiteralPath $installer).Length
    $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$installer.sha256" -Value "$hash  $name" -Encoding ASCII

    $assetUrl = "$IndexRoot$Platform/$Version/$name"
    $assets = @(
        @{ name = $name; browser_download_url = $assetUrl; size = $installerSize }
        @{ name = "$name.sha256"; browser_download_url = "$assetUrl.sha256"; size = (Get-Item -LiteralPath "$installer.sha256").Length }
    )
    $index = @{ tag_name = "v$Version"; body = ''; prerelease = $false; assets = $assets } | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText((Join-Path $uploadDir 'latest.json'), $index)
    return $uploadDir
}

# 标准 ZIP 按字节分卷，95MiB 小于 Gitee 的 100MB；.001 可用 7-Zip 打开。
function Split-UpdatePackage {
    param([string]$Source, [string]$Dest, [string]$BaseName, [long]$PartSize = 95MB)
    if ($PartSize -le 0 -or $PartSize -gt 95MB) { throw 'Invalid update part size' }
    if ($BaseName -notmatch '^RWJS-[A-Za-z0-9-]+$') { throw 'Invalid update package name' }
    $resolvedDest = (Resolve-Path -LiteralPath $Dest).Path
    $zipPath = Join-Path $resolvedDest "$BaseName.zip"
    Get-ChildItem -LiteralPath $resolvedDest -File | Where-Object {
        $_.Name -eq "$BaseName.zip" -or $_.Name -eq "$BaseName.zip.sha256" -or
        $_.Name -match ('^' + [regex]::Escape($BaseName) + '\.zip\.\d+$')
    } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    Compress-Archive -LiteralPath $Source -DestinationPath $zipPath -CompressionLevel NoCompression -Force
    try {
        $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Set-Content -LiteralPath "$zipPath.sha256" -Value "$hash  $BaseName.zip" -Encoding ASCII
        $inputStream = [IO.File]::OpenRead($zipPath)
        try {
            $index = 0
            $buffer = New-Object byte[] (1MB)
            while ($inputStream.Position -lt $inputStream.Length) {
                $index++
                $partPath = '{0}.{1:D3}' -f $zipPath, $index
                $outputStream = [IO.File]::Create($partPath)
                try {
                    $written = 0L
                    while ($written -lt $PartSize -and $inputStream.Position -lt $inputStream.Length) {
                        $read = $inputStream.Read($buffer, 0, [int][Math]::Min($buffer.Length, $PartSize - $written))
                        $outputStream.Write($buffer, 0, $read)
                        $written += $read
                    }
                } finally { $outputStream.Dispose() }
            }
        } finally { $inputStream.Dispose() }
        Write-Host ("分卷包: {0}.zip.001 ~ {0}.zip.{1:D3} + SHA-256" -f $BaseName, $index)
    } finally { Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue }
}
