# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
param(
    [string]$Gateways = 'https://host.docker.internal:18450,https://host.docker.internal:18451',
    [string]$Image = 'ololab/olo-toolgate-device:dev',
    [string]$Name = 'toolgate-device',
    [string]$Project = 'toolgate-device',
    [string]$Platform = 'linux/amd64',
    [string]$CaFile = '',
    [string]$ConsoleUrls = '',
    [string]$Network = '',
    [switch]$NonInteractive
)
$ErrorActionPreference = 'Stop'
function Select-Input([string]$Key, [string]$Label, [string]$Default) {
    if ($script:Arguments.ContainsKey($Key) -or $NonInteractive -or [Console]::IsInputRedirected) { return $Default }
    $display = if ($Default) { $Default } else { 'none / automatic' }
    $override = Read-Host "$Label [$display] (Enter keeps default)"
    if ($override.Trim()) { return $override.Trim() }
    return $Default
}
function Invoke-Docker([string[]]$Values) {
    & docker @Values
    if ($LASTEXITCODE -ne 0) { throw "Docker failed (exit $LASTEXITCODE)." }
}
$script:Arguments = $PSBoundParameters
try {
    $Gateways = Select-Input 'Gateways' 'Comma-separated gateway URLs or container_name:8443 entries' $Gateways
    $Image = Select-Input 'Image' 'Device image' $Image
    $Name = Select-Input 'Name' 'Device hostname' $Name
    $Project = Select-Input 'Project' 'Compose project (separate project means separate device identity)' $Project
    $Platform = Select-Input 'Platform' 'Container platform' $Platform
    $CaFile = Select-Input 'CaFile' 'Optional public CA certificate file' $CaFile
    $ConsoleUrls = Select-Input 'ConsoleUrls' 'Optional paired local console URLs (blank discovers Docker defaults)' $ConsoleUrls
    $Network = Select-Input 'Network' 'Existing Docker network for container-name gateways (blank uses a new network)' $Network
    if ($Project -cnotmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Compose project must contain lowercase letters, digits, underscores or hyphens.' }
    if ($Name -notmatch '^[a-z0-9][a-z0-9_.-]{0,62}$') { throw 'Invalid device hostname.' }
    if ($Platform -notin @('linux/amd64','linux/arm64')) { throw 'Use linux/amd64 or linux/arm64.' }
    $env:TOOLGATE_GATEWAY_URLS = $Gateways
    $env:TOOLGATE_DEVICE_IMAGE = $Image
    $env:TOOLGATE_DEVICE_NAME = $Name
    $env:TOOLGATE_DEVICE_PLATFORM = $Platform
    $env:TOOLGATE_GATEWAY_CONSOLE_URLS = $ConsoleUrls
    $compose = @('compose','--project-name',$Project,'-f',(Join-Path $PSScriptRoot 'compose.yaml'))
    if ($Network) {
        $env:TOOLGATE_DEVICE_NETWORK = $Network
        $compose += @('-f',(Join-Path $PSScriptRoot 'compose.network.yaml'))
    }
    if ($CaFile) {
        $env:TOOLGATE_GATEWAY_CA_SOURCE = (Resolve-Path -LiteralPath $CaFile).Path
        if (-not (Test-Path -LiteralPath $env:TOOLGATE_GATEWAY_CA_SOURCE -PathType Leaf)) { throw 'The CA must be a regular certificate file.' }
        $compose += @('-f',(Join-Path $PSScriptRoot 'compose.ca.yaml'))
    }
    Invoke-Docker ($compose + @('config','--quiet'))
    Invoke-Docker ($compose + @('pull','device'))
    Invoke-Docker ($compose + @('up','-d','--force-recreate','--wait','--wait-timeout','180','device'))
    Write-Host "Device deployed from $Image. It will connect when a gateway is available."
    Write-Host "View approval details: docker compose --project-name $Project -f `"$PSScriptRoot/compose.yaml`" logs -f device"
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
