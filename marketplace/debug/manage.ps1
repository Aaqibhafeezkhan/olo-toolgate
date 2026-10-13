# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Brings up the whole local setup on Docker Desktop: the ToolGate debug stack (debug\manage.ps1), one Linux debug
# device (debug\create-devices.ps1) and OLO Orgs in its two containers, Drupal and the helper (standalone\compose.yaml).
# -MarketplaceOnly leaves ToolGate alone. The Marketplace images are built when missing (or with -Build, and on
# restart) inside a Linux builder container, so Windows needs neither bash nor PHP. The signing key is created once.
param(
    [ValidateSet('start', 'stop', 'restart')][string]$Operation = 'start',
    [switch]$MarketplaceOnly,
    [switch]$Build,
    [switch]$FullCheck,
    [switch]$FocusedCheck,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$marketplace = Split-Path $PSScriptRoot -Parent
$root = Split-Path $marketplace -Parent
$port = if ($env:OLO_ORGS_PORT) { $env:OLO_ORGS_PORT } else { '8080' }
$siteImage = if ($env:OLO_ORGS_IMAGE) { $env:OLO_ORGS_IMAGE } else { 'olo-orgs-standalone:local' }
$helperImage = if ($env:OLO_HELPER_IMAGE) { $env:OLO_HELPER_IMAGE } else { 'olo-toolgate-marketplace-helper:local' }
$builderImage = 'olo-orgs-builder:local'
$compose = @('compose', '-f', "$marketplace/standalone/compose.yaml")

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE. Deployment was not continued." }
}

# The debug scripts run in their own PowerShell so their exit codes and Push-Location stay theirs.
function Invoke-DebugScript([string]$Script, [string[]]$Arguments) {
    $shell = (Get-Process -Id $PID).Path
    & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "debug/$Script") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "debug\$Script failed with exit code $LASTEXITCODE. Deployment was not continued." }
}

function Test-Image([string]$Image) {
    & docker image inspect $Image *> $null
    return $LASTEXITCODE -eq 0
}

function Build-MarketplaceImages {
    Write-Host 'Building the Marketplace images (helper, example packages, Drupal, standalone site)...'
    $revision = (& git -C $root rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) { $revision = 'unknown' }
    $arguments = @('run', '--rm', '-v', '/var/run/docker.sock:/var/run/docker.sock', '-v', "${root}:/src",
        '-w', '/src/marketplace', '-e', "TOOLGATE_REVISION=$revision")
    foreach ($name in @('HTTPS_PROXY', 'https_proxy', 'NO_PROXY', 'no_proxy')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { $arguments += @('-e', "$name=$value") }
    }
    Invoke-Checked docker @('build', '-q', '-t', $builderImage, '-f', "$marketplace/scripts/builder.Dockerfile", "$marketplace/scripts")
    Invoke-Checked docker ($arguments + @($builderImage, 'bash', 'scripts/build-images.sh', '--core-only', '--standalone'))
}

function Initialize-SigningKey {
    & docker @compose --profile init-key run --rm --no-deps --entrypoint test init-key -e /run/olo-key/marketplace_signing_key
    if ($LASTEXITCODE -eq 0) { return }
    Write-Host 'Creating the Marketplace signing key (once)...'
    Invoke-Checked docker ($compose + @('--profile', 'init-key', 'run', '--rm', 'init-key'))
}

function Wait-Marketplace {
    # The first start installs the site and publishes the examples, which outlasts Drupal's healthcheck start period.
    $deadline = [DateTime]::UtcNow.AddMinutes(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        $states = @(foreach ($service in @('drupal', 'helper')) {
            $id = (& docker @compose ps -q $service)
            if ($id) { & docker inspect --format '{{.State.Health.Status}}' $id } else { 'missing' }
        })
        if (($states | Where-Object { $_ -ne 'healthy' }).Count -eq 0) { return }
        Start-Sleep -Seconds 5
    }
    & docker @compose logs --tail 40
    throw 'OLO Orgs did not become healthy within 15 minutes. Inspect: docker compose -p olo-orgs-standalone logs'
}

Push-Location $root
try {
    Invoke-Checked docker @('info', '--format', '{{.OSType}}')
    Invoke-Checked docker ($compose + @('config', '--quiet'))
    if ($FocusedCheck -and $FullCheck) { throw 'Choose -FocusedCheck or -FullCheck, not both.' }
    $checks = @()
    if ($FocusedCheck) { $checks += '-FocusedCheck' }
    if ($FullCheck) { $checks += '-FullCheck' }
    if ($ValidateOnly) {
        if (-not $MarketplaceOnly) { Invoke-DebugScript 'manage.ps1' @('start', '-ValidateOnly') }
        return
    }

    if ($Operation -eq 'stop') {
        Invoke-Checked docker ($compose + @('stop'))
        if (-not $MarketplaceOnly) {
            $devices = @(& docker ps -q --filter 'label=io.ololabs.toolgate.debug-device')
            if ($devices) { Invoke-Checked docker (@('stop') + $devices) }
            Invoke-DebugScript 'manage.ps1' @('stop')
        }
        return
    }

    if (-not $MarketplaceOnly) {
        Invoke-DebugScript 'manage.ps1' (@($Operation) + $checks)
        Invoke-DebugScript 'create-devices.ps1' @('-Count', '1')
    }

    if ($Build -or $Operation -eq 'restart' -or -not ((Test-Image $siteImage) -and (Test-Image $helperImage))) {
        Build-MarketplaceImages
    }
    Initialize-SigningKey
    $up = @('up', '-d', '--pull', 'never')
    if ($Operation -eq 'restart') { $up += '--force-recreate' }
    Invoke-Checked docker ($compose + $up)
    Wait-Marketplace
    Write-Host "OLO Orgs: http://localhost:$port/"
    Write-Host 'Admin password: docker compose -p olo-orgs-standalone exec drupal cat /var/lib/olo-orgs/secrets/admin_password'
    Start-Process "http://localhost:$port/"
} catch {
    Write-Host $_ -ForegroundColor Red
    exit 1
} finally { Pop-Location }
