# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
param([Parameter(Mandatory=$true)][string]$Python)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$version = (Get-Content "$root/VERSION" -Raw).Trim()
$githubHeaders = @{
    Accept = 'application/vnd.github+json'
    'User-Agent' = 'toolgate-debug-client-assets'
}
if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
    $githubHeaders.Authorization = 'Bearer ' + $env:GITHUB_TOKEN.Trim()
}
$source = Join-Path $root '.dev/debug/client-assets'
New-Item -ItemType Directory -Force $source | Out-Null
$usingCachedInputs = $false
try {
    $releases = Invoke-RestMethod -Uri 'https://api.github.com/repos/olo-labs/olo-toolgate/releases?per_page=30' -Headers $githubHeaders
} catch {
    # Public API quotas must not strand a previously verified local bundle.
    # Invalid supplied credentials still fail; never silently retry without them.
    $cached = @(Get-ChildItem -LiteralPath $source -Filter "olo-toolgate-client-$version-*.run" -File)
    if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN) -or -not $cached.Count) { throw }
    Write-Host 'GitHub release lookup is unavailable. Rebuilding installers from cached native inputs; canonical checksum and platform validation remain required.'
    $releases = @()
    $usingCachedInputs = $true
}
$release = $releases | Where-Object {
    -not $_.draft -and ($_.assets.name -contains 'installers.json') -and
    ($_.assets.name -contains "olo-toolgate-client-$version-x86_64-pc-windows-msvc.setup.exe")
} | Select-Object -First 1
if (-not $release -and -not $usingCachedInputs) { throw "No published client installer bundle found for $version." }
foreach ($asset in $release.assets) {
    if ($asset.name -notmatch ('^olo-toolgate-client-' + [regex]::Escape($version) + '-[a-z0-9_-]+\.(zip|tar\.gz|setup\.exe|dmg|run)(\.sha256)?$')) { continue }
    # Windows releases may predate the combined installer. Preserve compatible
    # internal CI archives already supplied locally instead of overwriting them.
    if ($asset.name -match '-[a-z0-9_]+-pc-windows-') { continue }
    Write-Host "Preparing $($asset.name)"
    & curl.exe --fail --location --silent --show-error --retry 2 --connect-timeout 20 --max-time 180 --output (Join-Path $source $asset.name) $asset.browser_download_url
    if ($LASTEXITCODE -ne 0) { throw "Could not download $($asset.name)." }
}
# Repackage compatible native CI inputs, or compile the current x64 Windows
# client and native host together using Docker when cached archives are legacy.
$compiler = Join-Path $root '.dev/debug/toolgate-inno-6.7.1/ISCC.exe'
if (-not (Test-Path -LiteralPath $compiler)) {
    $previousTemp = $env:RUNNER_TEMP
    try {
        $env:RUNNER_TEMP = Join-Path $root '.dev/debug'
        & "$root/tools/client/installer-compiler.ps1"
    } finally { $env:RUNNER_TEMP = $previousTemp }
}
$previousCompiler = $env:CLIENT_ISCC_PATH
try {
    $env:CLIENT_ISCC_PATH = $compiler
    & $Python "$root/tools/client/debug_assets.py" --source $source --output "$root/deploy/client-assets/release"
    if ($LASTEXITCODE -ne 0) { throw 'Combined Windows client and release bundle build failed.' }
} finally { $env:CLIENT_ISCC_PATH = $previousCompiler }
if ($release) { Write-Host "Verified native installers from $($release.tag_name)" }
else { Write-Host 'Verified native installers from the cached inputs.' }
