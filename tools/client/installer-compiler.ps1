# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
$toolgateCompilerDirectory = Join-Path $env:RUNNER_TEMP 'toolgate-inno-6.7.1'
# CI restores this directory from actions/cache; the checksum below still gates every use.
$toolgateCompilerDownloads = Join-Path $env:RUNNER_TEMP 'toolgate-inno-download'
$toolgateCompilerSetup = Join-Path $toolgateCompilerDownloads 'innosetup-6.7.1.exe'
# Checksum published in Microsoft's winget package manifest for this exact release.
$toolgateCompilerSha256 = '4d11e8050b6185e0d49bd9e8cc661a7a59f44959a621d31d11033124c4e8a7b0'
$toolgateCompilerRelease = 'https://api.github.com/repos/jrsoftware/issrc/releases/tags/is-6_7_1'
$toolgateCompilerUrl = 'https://github.com/jrsoftware/issrc/releases/download/is-6_7_1/innosetup-6.7.1.exe'

function Test-ToolgateCompilerSetup {
    (Test-Path -LiteralPath $toolgateCompilerSetup) -and
        (Get-FileHash -LiteralPath $toolgateCompilerSetup -Algorithm SHA256).Hash.ToLowerInvariant() -eq $toolgateCompilerSha256
}

# One source with bounded retries and backoff.
function Receive-ToolgateCompilerSetup([string]$source, [hashtable]$headers) {
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $source -Headers $headers -OutFile $toolgateCompilerSetup -TimeoutSec 600
            if (Test-ToolgateCompilerSetup) { return $true }
            Write-Warning "Installer compiler checksum mismatch from $source"
            return $false
        } catch {
            Write-Warning "Installer compiler download attempt $attempt from $source failed: $($_.Exception.Message)"
            # Like curl --retry, a permanent client error moves on to the next source at once.
            $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            if ($status -ge 400 -and $status -lt 500 -and $status -ne 408 -and $status -ne 429) { break }
            if ($attempt -lt 4) { Start-Sleep -Seconds (5 * $attempt) }
        }
    }
    return $false
}

New-Item -ItemType Directory -Force -Path $toolgateCompilerDownloads | Out-Null
if (!(Test-ToolgateCompilerSetup)) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $received = Receive-ToolgateCompilerSetup $toolgateCompilerUrl @{}
    if (!$received) {
        # github.com release downloads can fail while the API asset endpoint, which
        # redirects to the same asset on GitHub's CDN, still serves it.
        try {
            $release = Invoke-RestMethod -UseBasicParsing -Uri $toolgateCompilerRelease -TimeoutSec 60
            $asset = $release.assets | Where-Object { $_.name -eq 'innosetup-6.7.1.exe' } | Select-Object -First 1
            if ($asset) { $received = Receive-ToolgateCompilerSetup $asset.url @{ Accept = 'application/octet-stream' } }
        } catch {
            Write-Warning "Installer compiler release lookup failed: $($_.Exception.Message)"
        }
    }
    if (!$received) {
        if (Test-Path -LiteralPath $toolgateCompilerSetup) { Remove-Item -LiteralPath $toolgateCompilerSetup -Force }
        throw 'Installer compiler could not be downloaded with the pinned checksum'
    }
}
$toolgateCompilerProcess = Start-Process -FilePath $toolgateCompilerSetup -ArgumentList @('/CURRENTUSER','/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="' + $toolgateCompilerDirectory + '"'),'/NOICONS','/TASKS=""') -WindowStyle Hidden -Wait -PassThru
if ($toolgateCompilerProcess.ExitCode -ne 0) { throw 'Installer compiler installation failed' }
$toolgateCompilerPath = Join-Path $toolgateCompilerDirectory 'ISCC.exe'
if (!(Test-Path -LiteralPath $toolgateCompilerPath)) { throw 'Installer compiler missing' }
if ($env:GITHUB_ENV) { "CLIENT_ISCC_PATH=$toolgateCompilerPath" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8 }
