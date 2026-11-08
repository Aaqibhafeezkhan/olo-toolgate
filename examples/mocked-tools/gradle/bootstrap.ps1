# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
$properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'wrapper/gradle-wrapper.properties')
function Get-WrapperProperty([string]$name) {
    $line = $properties | Where-Object { $_ -like "$name=*" } | Select-Object -First 1
    if ($line) { $line.Substring($name.Length + 1).Replace('\:', ':').Trim() } else { $null }
}
$url = Get-WrapperProperty 'distributionUrl'
$fallbackUrl = Get-WrapperProperty 'distributionFallbackUrl'
$checksum = Get-WrapperProperty 'distributionSha256Sum'
$version = [regex]::Match($url, 'gradle-([0-9.]+)-bin').Groups[1].Value
$cacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$bootstrapRoot = Join-Path $cacheRoot 'olo-toolgate-bootstrap'
$installDir = Join-Path $bootstrapRoot "gradle-$version"
$launcher = Join-Path $installDir 'bin/gradle.bat'

function Get-Sha256([string]$path) {
    $stream = [IO.File]::OpenRead($path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha256.Dispose() }
}

# One source with bounded retries and backoff. GitHub API asset URLs need an
# explicit octet-stream Accept header; the redirect lands on GitHub's asset CDN.
function Receive-Source([string]$source, [string]$destination) {
    $headers = @{}
    if ($source.StartsWith('https://api.github.com/')) { $headers['Accept'] = 'application/octet-stream' }
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Force }
            Invoke-WebRequest -UseBasicParsing -Uri $source -Headers $headers -OutFile $destination -TimeoutSec 900
            return $true
        } catch {
            Write-Warning "Gradle download attempt $attempt from $source failed: $($_.Exception.Message)"
            # Like curl --retry, a permanent client error moves on to the next source at once.
            $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            if ($status -ge 400 -and $status -lt 500 -and $status -ne 408 -and $status -ne 429) { break }
            if ($attempt -lt 4) { Start-Sleep -Seconds (5 * $attempt) }
        }
    }
    return $false
}

if (!(Test-Path -LiteralPath $launcher)) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    New-Item -ItemType Directory -Force -Path $bootstrapRoot | Out-Null
    $archive = Join-Path $bootstrapRoot "gradle-$version-bin.zip"
    if ((Test-Path -LiteralPath $archive) -and (Get-Sha256 $archive) -ne $checksum) {
        Remove-Item -LiteralPath $archive -Force
    }
    if (!(Test-Path -LiteralPath $archive)) {
        Write-Host "Bootstrapping Gradle $version..."
        $part = "$archive.part-$PID"
        # An explicit mirror (for example a corporate proxy) is tried first. Every
        # source must produce the exact pinned archive: a source adds availability,
        # never a different distribution.
        $sources = @($env:TOOLGATE_GRADLE_DIST_URL, $url, $fallbackUrl) | Where-Object { $_ }
        try {
            foreach ($source in $sources) {
                if (!(Receive-Source $source $part)) { continue }
                $actual = Get-Sha256 $part
                if ($actual -eq $checksum) { Move-Item -LiteralPath $part -Destination $archive -Force; break }
                Write-Warning "Gradle checksum mismatch from $source (got $actual); trying the next source."
            }
        } finally {
            if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }
        }
        if (!(Test-Path -LiteralPath $archive)) {
            throw "Gradle $version could not be downloaded with SHA-256 $checksum from any source"
        }
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
