<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
function Read-RwUpdateIndex ([string]$IndexUrl) {
    try {
        $response = Invoke-WebRequest -Uri $IndexUrl -TimeoutSec 12 -UseBasicParsing
        [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()).TrimStart([char]0xfeff) | ConvertFrom-Json
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) {
            [pscustomobject]@{ tag_name = 'latest'; assets = @() }
        } else { throw }
    }
}

function Write-RwUpdateBundle {
    param([string]$Dest, [string]$IndexUrl, [string]$Version,
        [hashtable]$Installers = @{}, [IO.FileInfo[]]$KernelFiles = @())
    $uri = $null
    if (-not [Uri]::TryCreate($IndexUrl, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        $uri.AbsolutePath -cnotmatch '^/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*version\.json$') {
        throw 'R2 更新清单必须使用有效的 HTTPS version.json 地址。'
    }
    if ($Version -cnotmatch '^\d+\.\d+\.\d+$') { throw 'Invalid client update version' }
    $indexBase = $uri.AbsoluteUri -creplace 'version\.json$', ''
    $authority = $uri.GetLeftPart([UriPartial]::Authority)
    $previous = Read-RwUpdateIndex $IndexUrl
    if (-not $previous.tag_name -or $null -eq $previous.assets) { throw '现有 R2 更新清单无效。' }
    $assets = [ordered]@{}
    foreach ($asset in $previous.assets) {
        $assetName = [string]$asset.name
        if (-not $assetName -or -not $asset.browser_download_url) { throw '现有 R2 清单存在无效文件项。' }
        if ($assetName -cmatch '^RWJS-Browser-[A-Za-z0-9_.+-]+\.(json|kernel\.\d{3})$' -and
            $asset.browser_download_url -ceq "$authority/$assetName") {
            $asset.browser_download_url = "$indexBase$assetName"
        }
        $assets[$assetName] = $asset
    }

    $indexPath = ($uri.AbsolutePath -creplace 'version\.json$', '').Trim('/')
    $uploadDir = Join-Path $Dest "upneed/$indexPath"
    New-Item -ItemType Directory -Path $uploadDir -Force | Out-Null
    $versions = [ordered]@{}
    foreach ($platform in @('windows', 'android')) {
        $extension = if ($platform -eq 'windows') { 'exe' } else { 'apk' }
        $pattern = '\.' + $extension + '(\.sha256)?$'
        if ($Installers.ContainsKey($platform)) {
            $stem = if ($platform -eq 'windows') { 'RWJS-Setup' } else { 'RWJS-Android' }
            $name = "$stem-$Version.$extension"
            $installer = Join-Path $uploadDir $name
            Copy-Item -LiteralPath $Installers[$platform] -Destination $installer
            $size = (Get-Item -LiteralPath $installer).Length
            if ($size -le 0) { throw 'Client update installer is empty' }
            $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
            Set-Content -LiteralPath "$installer.sha256" -Value "$hash  $name" -Encoding ASCII
            foreach ($key in @($assets.Keys | Where-Object { $_ -match $pattern })) { $assets.Remove($key) }
            $assets[$name] = @{ name = $name; browser_download_url = "$indexBase$name"; size = $size }
            $assets["$name.sha256"] = @{ name = "$name.sha256"; browser_download_url = "$indexBase$name.sha256"; size = (Get-Item -LiteralPath "$installer.sha256").Length }
            $versions[$platform] = "v$Version"
        } else {
            $priorVersion = $previous.platform_versions.$platform
            if (-not $priorVersion -and $null -eq $previous.platform_versions -and
                @($assets.Keys | Where-Object { $_ -match ('\.' + $extension + '$') }).Count) {
                $priorVersion = $previous.tag_name
            }
            if ($priorVersion) { $versions[$platform] = $priorVersion }
        }
    }
    foreach ($file in $KernelFiles) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $uploadDir $file.Name)
        $assets[$file.Name] = @{ name = $file.Name; browser_download_url = "$indexBase$($file.Name)"; size = $file.Length }
    }
    $index = [ordered]@{
        tag_name = if ($Installers.Count) { "v$Version" } else { $previous.tag_name }
        body = [string]$previous.body
        prerelease = (-not $Installers.Count -and [bool]$previous.prerelease)
        platform_versions = $versions
        assets = @($assets.Values)
    } | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText((Join-Path $uploadDir 'version.json'), $index)
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
