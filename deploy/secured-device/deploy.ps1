# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
param(
    [string]$GatewayContainer = 'toolgate-debug-quickstart-1',
    [string]$GatewayUrl = '',
    [int]$GatewayPort = 8443,
    [string]$Network = '',
    [string]$CaFile = '',
    [string]$Image = 'ololab/olo-toolgate-linux-secured-device:dev',
    [string]$Name = 'toolgate-secured-device',
    [string]$Project = 'toolgate-secured-device',
    [switch]$NoPull
)
$ErrorActionPreference = 'Stop'
function Invoke-Docker([string[]]$Values) {
    $output = & docker @Values
    if ($LASTEXITCODE -ne 0) { throw "Docker failed (exit $LASTEXITCODE)." }
    return $output
}
try {
    if ($Project -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Invalid Compose project.' }
    if ($Name -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}$') { throw 'Invalid device hostname.' }
    if ($GatewayPort -lt 1 -or $GatewayPort -gt 65535) { throw 'Invalid Gateway port.' }
    $gateway = ((Invoke-Docker @('inspect', $GatewayContainer)) -join "`n" | ConvertFrom-Json)[0]
    if (-not $gateway.State.Running) { throw 'Start the Gateway container first.' }
    $networks = @($gateway.NetworkSettings.Networks.PSObject.Properties)
    if (-not $Network) {
        if ($networks.Count -ne 1) { throw 'Specify -Network when the Gateway has multiple networks.' }
        $Network = $networks[0].Name
    }
    $selected = @($networks | Where-Object Name -eq $Network)
    if ($selected.Count -ne 1 -or -not $selected[0].Value.IPAddress) { throw 'Gateway needs an IPv4 address on the selected Docker network.' }
    if (-not $GatewayUrl) {
        $endpoint = @($gateway.Config.Env | Where-Object { $_.StartsWith('TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL=') })
        if ($endpoint.Count -ne 1) { throw 'Specify -GatewayUrl with the advertised HTTPS device endpoint.' }
        $GatewayUrl = $endpoint[0].Substring('TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL='.Length)
    }
    $url = [Uri]$GatewayUrl
    if (-not $url.IsAbsoluteUri -or $url.Scheme -ne 'https' -or $url.UserInfo -or $url.Query -or $url.Fragment -or $url.AbsolutePath -ne '/') {
        throw 'Supply exactly one HTTPS Gateway origin.'
    }
    $state = Join-Path $PSScriptRoot ".state/$Project"
    New-Item -ItemType Directory -Force -Path $state | Out-Null
    $certificate = Join-Path $state 'gateway-ca.crt'
    if ($CaFile) {
        Copy-Item -LiteralPath (Resolve-Path -LiteralPath $CaFile).Path -Destination $certificate
    } else {
        # Quickstart's public CA only. Never mount its keys, state volume or Docker socket.
        Invoke-Docker @('cp', "${GatewayContainer}:/data/keys/device-ca.crt", $certificate) | Out-Host
    }
    $env:TOOLGATE_GATEWAY_CA_SOURCE = (Resolve-Path -LiteralPath $certificate).Path
    $env:TOOLGATE_GATEWAY_URLS = $GatewayUrl
    $env:TOOLGATE_SECURED_GATEWAY_IPV4 = $selected[0].Value.IPAddress
    $env:TOOLGATE_SECURED_GATEWAY_PORT = "$GatewayPort"
    $env:TOOLGATE_DEVICE_NETWORK = $Network
    $env:TOOLGATE_SECURED_DEVICE_IMAGE = $Image
    $env:TOOLGATE_DEVICE_NAME = $Name
    $env:TOOLGATE_DEVICE_PULL_POLICY = if ($NoPull) { 'never' } else { 'always' }
    $compose = @('compose', '--project-name', $Project, '-f', (Join-Path $PSScriptRoot 'compose.yaml'))
    Invoke-Docker ($compose + @('config', '--quiet')) | Out-Host
    Invoke-Docker ($compose + @('up', '-d', '--force-recreate', '--wait', '--wait-timeout', '180', 'device')) | Out-Host
    Write-Host "Secured device started for $GatewayUrl via $($selected[0].Value.IPAddress):$GatewayPort."
    Invoke-Docker ($compose + @('logs', '--tail', '20', 'device')) | Out-Host
    Write-Host 'Approve the enrollment code/fingerprint in the Gateway console; the device then connects automatically.'
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
