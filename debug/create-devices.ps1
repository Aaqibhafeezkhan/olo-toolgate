# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
param(
    [Alias('LinuxCount')][int]$Count = -1,
    [string]$DockerContext = '',
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$deviceRoot = Split-Path $PSScriptRoot -Parent
$deviceImage = 'olo-toolgate-linux-device:debug'
$deviceLabel = 'io.ololabs.toolgate.debug-device'
$deviceDocker = @()
if ($DockerContext) { $deviceDocker = @('--context', $DockerContext) }

function Invoke-DeviceDocker([string[]]$Arguments) {
    & docker @deviceDocker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed (exit $LASTEXITCODE). See the output above." }
}

function Read-DeviceCount {
    while ($true) {
        $answer = Read-Host 'How many Linux devices should be created? [1]'
        if ([string]::IsNullOrWhiteSpace($answer)) { return 1 }
        $number = 0
        if ($answer -match '^\d+$' -and [int]::TryParse($answer, [ref]$number) -and $number -le 32) { return $number }
        Write-Host 'Enter a whole number from 0 to 32.' -ForegroundColor Yellow
    }
}

function Get-DeviceStatus([string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        $status = & docker @deviceDocker exec $Name sh -c 'test -f /run/olo-toolgate/device-status.json && cat /run/olo-toolgate/device-status.json' 2>$null
        if ($LASTEXITCODE -eq 0) {
            $parsed = ($status -join "`n") | ConvertFrom-Json
            if ($parsed.deviceId) { return $parsed }
        }
        $running = Invoke-DeviceDocker @('inspect', '--format', '{{.State.Running}}', $Name)
        if ($running -ne 'true') { break }
        Start-Sleep -Seconds 2
    }
    & docker @deviceDocker logs --tail 30 $Name
    throw "$Name did not submit an enrollment request. Inspect: docker logs $Name"
}

try {
    if ($Count -eq -1) { $Count = if ($ValidateOnly) { 1 } else { Read-DeviceCount } }
    if ($Count -lt 0 -or $Count -gt 32) { throw 'The Linux device count must be between 0 and 32.' }
    if ($Count -eq 0) { Write-Host 'No Linux devices requested.'; return }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install Docker Desktop and start its Linux engine.' }
    $engine = Invoke-DeviceDocker @('info', '--format', '{{.OSType}}')
    if ($engine -ne 'linux') { throw 'Linux devices require a Linux Docker engine. Switch Docker Desktop to Linux containers.' }
    $serverIds = @(Invoke-DeviceDocker @('ps', '--filter', 'label=com.docker.compose.project=toolgate-debug', '--filter', 'label=com.docker.compose.service=quickstart', '--format', '{{.ID}}'))
    if ($serverIds.Count -ne 1) { throw 'Start the local debug stack with debug\start.bat before creating devices.' }
    $server = ((Invoke-DeviceDocker @('inspect', $serverIds[0])) -join "`n" | ConvertFrom-Json)[0]
    if ($server.State.Health.Status -ne 'healthy') { throw 'The debug Quickstart container must be healthy before creating devices.' }
    if ($server.Config.Env -notcontains 'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL=https://localhost:18450') {
        throw 'Restart debug\start.bat to use the expected local endpoint https://localhost:18450.'
    }
    $network = @($server.NetworkSettings.Networks.PSObject.Properties)[0].Name
    $upstream = $server.Name.TrimStart('/')
    $existing = @(Invoke-DeviceDocker @('ps', '-a', '--format', '{{.Names}}'))
    $needsBuild = $false
    # Check every requested slot before building or changing any container.
    for ($index = 1; $index -le $Count; $index++) {
        $name = 'toolgate-device-linux-{0:d3}' -f $index
        if ($existing -contains $name) {
            $container = ((Invoke-DeviceDocker @('inspect', $name)) -join "`n" | ConvertFrom-Json)[0]
            $owner = $container.Config.Labels.$deviceLabel
            if ($owner -ne 'linux') { throw "Container $name already exists and is not managed by create-devices.bat." }
        } else { $needsBuild = $true }
    }
    Write-Host "Docker is ready; $Count Linux device(s) will connect to $upstream."
    if ($ValidateOnly) { Write-Host 'Validation passed. No containers were changed.'; return }
    if ($needsBuild) {
        Write-Host 'Building the Linux device image from the current client source. The first build can take several minutes.'
        Invoke-DeviceDocker @('build', '--platform', 'linux/amd64', '-f', "$PSScriptRoot/linux-device.Dockerfile", '-t', $deviceImage, $deviceRoot)
    }
    $statuses = @()
    for ($index = 1; $index -le $Count; $index++) {
        $name = 'toolgate-device-linux-{0:d3}' -f $index
        if ($existing -contains $name) {
            # Reuse containers as well as keys. Approval remains an explicit console action.
            Write-Host "Starting existing device $name"
            Invoke-DeviceDocker @('start', $name)
        } else {
            Write-Host "Creating $name"
            Invoke-DeviceDocker @('run', '-d', '--name', $name, '--hostname', $name, '--restart', 'unless-stopped',
                '--stop-timeout', '30', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                '--network', $network, '--tmpfs', '/run:rw,nosuid,size=8m,mode=755',
                '--label', "$deviceLabel=linux", '-e', "TOOLGATE_DEVICE_UPSTREAM=$upstream",
                # Docker Desktop groups containers by Compose project, so the device is listed with the debug stack.
                '--label', 'com.docker.compose.project=toolgate-debug', '--label', "com.docker.compose.service=$name",
                '--mount', "type=volume,source=$name-config,target=/etc/olo-toolgate",
                '--mount', "type=volume,source=$name-state,target=/var/lib/olo-toolgate", $deviceImage)
        }
        $status = Get-DeviceStatus $name
        $statuses += [pscustomobject]@{ Container = $name; Device = $status.deviceId; State = $status.state; Code = $status.userCode; Fingerprint = $status.keyFingerprint }
        Write-Host "$name -> $($status.deviceId) ($($status.state))"
        if ($status.userCode) { Write-Host "Approval code: $($status.userCode); fingerprint: $($status.keyFingerprint)" }
    }
    $statuses | Format-Table Container, Device, State, Code -AutoSize | Out-Host
    Write-Host 'Open http://127.0.0.1:18090/console/#devices, compare the code/fingerprint, and approve each new Linux client.'
    Write-Host 'Clients connect automatically after approval. Docker volumes retain their identities across restarts.'
} catch {
    Write-Host $_ -ForegroundColor Red
    exit 1
}
