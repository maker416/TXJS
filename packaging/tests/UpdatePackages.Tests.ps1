<#
Copyright 2023-2025 RWPP contributors
此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../update-packages.ps1')
$testRoot = Join-Path $PSScriptRoot ('../../build/reports/packaging-tests/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

function Assert-UpdatePackage([string]$Name, [string]$Extension, [long]$PartSize) {
    $dest = Join-Path $testRoot "$Name-$PartSize"
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    $source = Join-Path $dest "$Name$Extension"
    $payload = New-Object byte[] 8192
    for ($i = 0; $i -lt $payload.Length; $i++) { $payload[$i] = $i % 251 }
    [IO.File]::WriteAllBytes($source, $payload)
    Split-UpdatePackage -Source $source -Dest $dest -BaseName $Name -PartSize $PartSize
    $parts = @(Get-ChildItem -LiteralPath $dest -File | Where-Object { $_.Name -match '\.zip\.\d+$' } | Sort-Object Name)
    if ($parts.Count -eq 0) { throw 'No volumes generated' }
    $merged = New-Object IO.MemoryStream
    try {
        foreach ($part in $parts) {
            if ($part.Length -le 0 -or $part.Length -gt $PartSize) { throw 'Invalid volume size' }
            $bytes = [IO.File]::ReadAllBytes($part.FullName)
            $merged.Write($bytes, 0, $bytes.Length)
        }
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $actual = [BitConverter]::ToString($sha.ComputeHash($merged.ToArray())).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        $expected = (Get-Content -LiteralPath (Join-Path $dest "$Name.zip.sha256") -Raw).Split(' ')[0]
        if ($expected -ne $actual) { throw 'SHA-256 does not match joined volumes' }
        $merged.Position = 0
        $archive = [IO.Compression.ZipArchive]::new($merged, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            if ($archive.Entries.Count -ne 1 -or $archive.Entries[0].Name -ne "$Name$Extension") { throw 'Unexpected installer entry' }
            $entry = $archive.Entries[0].Open()
            $unpacked = New-Object IO.MemoryStream
            try {
                $entry.CopyTo($unpacked)
                if ([Convert]::ToBase64String($unpacked.ToArray()) -ne [Convert]::ToBase64String($payload)) { throw 'Payload changed' }
            } finally { $entry.Dispose(); $unpacked.Dispose() }
        } finally { $archive.Dispose() }
        if (Test-Path -LiteralPath (Join-Path $dest "$Name.zip")) { throw 'Merged zip was not removed' }
        Write-Host "PASS $Name ($($parts.Count) volumes)"
        return $merged.Length
    } finally { $merged.Dispose() }
}

Assert-UpdatePackage 'RWJS-Setup' '.exe' 1024 | Out-Null
Assert-UpdatePackage 'RWJS-Android' '.apk' 1024 | Out-Null
$zipLength = Assert-UpdatePackage 'RWJS-Android' '.apk' 95MB
# 完整 ZIP 恰好一卷：不应多生成一个空卷。
Assert-UpdatePackage 'RWJS-Android' '.apk' $zipLength | Out-Null
Write-Host 'All update packaging tests passed.'
