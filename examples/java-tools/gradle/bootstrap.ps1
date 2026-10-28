# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
$properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'wrapper/gradle-wrapper.properties')
$url = ($properties | Where-Object { $_ -like 'distributionUrl=*' }).Substring(16).Replace('\:', ':')
$checksum = ($properties | Where-Object { $_ -like 'distributionSha256Sum=*' }).Substring(22)
$version = [regex]::Match($url, 'gradle-([0-9.]+)-bin').Groups[1].Value
$cacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$bootstrapRoot = Join-Path $cacheRoot 'olo-toolgate-bootstrap'
$installDir = Join-Path $bootstrapRoot "gradle-$version"
$launcher = Join-Path $installDir 'bin/gradle.bat'
if (!(Test-Path -LiteralPath $launcher)) {
    New-Item -ItemType Directory -Force -Path $bootstrapRoot | Out-Null
    $archive = Join-Path $bootstrapRoot "gradle-$version-bin.zip"
    if (!(Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive
    }
    $stream = [IO.File]::OpenRead($archive)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { $actualChecksum = [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha256.Dispose() }
    if ($actualChecksum -ne $checksum) {
        Remove-Item -LiteralPath $archive -Force
        throw 'Gradle distribution checksum mismatch'
    }
    $extractDir = Join-Path $bootstrapRoot ('.extract-' + [guid]::NewGuid())
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $extractDir)
    if (Test-Path -LiteralPath $installDir) { throw "Incomplete Gradle installation: $installDir" }
    $sourceDir = Join-Path $extractDir "gradle-$version"
    $resolvedBootstrap = [IO.Path]::GetFullPath($bootstrapRoot) + [IO.Path]::DirectorySeparatorChar
    foreach ($targetPath in @($sourceDir, $installDir)) {
        if (![IO.Path]::GetFullPath($targetPath).StartsWith($resolvedBootstrap, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Unsafe Gradle installation move path'
        }
    }
    Move-Item -LiteralPath $sourceDir -Destination $installDir
    $resolvedExtract = [IO.Path]::GetFullPath($extractDir)
    if (!$resolvedExtract.StartsWith([IO.Path]::GetFullPath($bootstrapRoot) + [IO.Path]::DirectorySeparatorChar)) {
        throw 'Unsafe Gradle extraction cleanup path'
    }
    Remove-Item -LiteralPath $resolvedExtract -Recurse -Force
}
& $launcher @args
exit $LASTEXITCODE
