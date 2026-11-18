# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
function Find-DebugPython {
    param([string[]]$Candidates = @(
        "$PSScriptRoot/../../.dev/profiled/python-3.14.10-amd64/python.exe",
        "$PSScriptRoot/../../.dev/profiled/python-3.14.10-arm64/python.exe",
        'python', 'python3'
    ), [switch]$InstallIfMissing)
    foreach ($candidate in $Candidates) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if (-not $command) { continue }
        $path = $command.Source
        # Store aliases can emit stderr or open the Store instead of running Python.
        if ($path -match '[\\/]Microsoft[\\/]WindowsApps[\\/]') { continue }
        $probe = New-Object System.Diagnostics.Process
        $probe.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
        $probe.StartInfo.FileName = $path
        $probe.StartInfo.Arguments = '--version'
        $probe.StartInfo.UseShellExecute = $false
        $probe.StartInfo.CreateNoWindow = $true
        $probe.StartInfo.RedirectStandardOutput = $true
        $probe.StartInfo.RedirectStandardError = $true
        try {
            [void]$probe.Start()
            if (-not $probe.WaitForExit(5000)) { $probe.Kill(); continue }
            $output = $probe.StandardOutput.ReadToEnd() + $probe.StandardError.ReadToEnd()
            if ($probe.ExitCode -eq 0 -and $output -match 'Python (\d+\.\d+\.\d+)') {
                if ([version]$Matches[1] -ge [version]'3.11.0') { return $path }
            }
        } catch {
            # A broken installation must not prevent trying the next candidate.
        } finally { $probe.Dispose() }
    }
    if ($InstallIfMissing) { return Install-DebugPython }
    throw 'No usable Python 3.11+ found. Run start.bat to install the private launcher runtime.'
}

function Install-DebugPython {
    $ErrorActionPreference = 'Stop'
    # Pinned official embeddable runtime; no admin rights, PATH changes or pip.
    # https://www.python.org/downloads/release/python-31410/
    $version = '3.14.10'
    $architecture = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64' -or $env:PROCESSOR_ARCHITEW6432 -eq 'ARM64') { 'arm64' } else { 'amd64' }
    $hashes = @{
        amd64 = '90905e64a840557d8c1bc4f5b74db3d7ee85e3ffb217611cac1c7813acea4159'
        arm64 = 'bbe116bb1248820359cbf25127ac5c7794ea161869d83686cfbc9a45203ff836'
    }
    $repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repository '.dev/profiled'))
    $destination = Join-Path $runtimeRoot "python-$version-$architecture"
    $executable = Join-Path $destination 'python.exe'
    if (Test-Path -LiteralPath $executable) {
        & $executable -c 'import ssl,sys; assert sys.version_info[:3] == (3,14,10)'
        if ($LASTEXITCODE -ne 0) { throw 'Private Python runtime is damaged; repair .dev/profiled explicitly.' }
        return $executable
    }
    if (Test-Path -LiteralPath $destination) { throw 'Incomplete private runtime preserved; repair .dev/profiled explicitly.' }
    [void](New-Item -ItemType Directory -Force -Path $runtimeRoot)
    $archive = Join-Path $runtimeRoot "python-$version-embed-$architecture.zip"
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Installing private Python $version ($architecture); Node and other build tools run inside Docker."
        $pending = "$archive.$([guid]::NewGuid().ToString('N')).part"
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $download = New-Object Net.WebClient
        try { $download.DownloadFile("https://www.python.org/ftp/python/$version/python-$version-embed-$architecture.zip", $pending) } finally { $download.Dispose() }
        if ((Get-DebugRuntimeHash $pending) -ne $hashes[$architecture]) { throw 'Python download checksum mismatch; nothing was executed.' }
        Move-Item -LiteralPath $pending -Destination $archive
    }
    if ((Get-DebugRuntimeHash $archive) -ne $hashes[$architecture]) { throw 'Cached Python checksum mismatch; nothing was executed.' }
    $staging = Join-Path $runtimeRoot ('extract-' + [guid]::NewGuid().ToString('N'))
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $staging)
    & (Join-Path $staging 'python.exe') -c 'import ssl,sys; assert sys.version_info[:3] == (3,14,10)'
    if ($LASTEXITCODE -ne 0) { throw 'Downloaded Python runtime did not pass verification.' }
    $resolvedStaging = (Resolve-Path -LiteralPath $staging).Path
    $resolvedDestination = [System.IO.Path]::GetFullPath($destination)
    if (-not $resolvedStaging.StartsWith($runtimeRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $resolvedDestination.StartsWith($runtimeRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Runtime move target is outside the private bootstrap directory.'
    }
    Move-Item -LiteralPath $resolvedStaging -Destination $resolvedDestination
    return $executable
}

function Get-DebugRuntimeHash([string]$Path) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}
