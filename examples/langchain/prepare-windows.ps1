# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Run explicitly as Administrator on a Windows device enrolled to THIS example.
param([string]$Gateway = 'https://localhost:18451', [string]$TrustPath = (Join-Path $PSScriptRoot '.state/deployment-trust.json'))
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run prepare-windows.ps1 in an Administrator PowerShell.'
}
$configPath = Join-Path $env:ProgramData 'OLO/ToolGate/client.json'
$binary = Join-Path $env:ProgramFiles 'OLO/ToolGate/olo-toolgate-client.exe'
if ((Get-Item -LiteralPath $configPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Use a regular protected client configuration.' }
$original = [IO.File]::ReadAllText($configPath)
$config = $original | ConvertFrom-Json
if ($config.serverUrl.TrimEnd('/') -ne $Gateway.TrimEnd('/')) { throw 'Enroll this device to the example Gateway first. This helper does not switch gateways or install clients.' }
$journal = Get-Content -LiteralPath (Join-Path $config.stateDirectory 'journal.json') -Raw | ConvertFrom-Json
$deviceId = $journal.identity.deviceId
if ($deviceId -notmatch '^device-[a-f0-9]{32}$') { throw 'Complete enrollment on this example Gateway before preparing its tools.' }
if (-not (Test-Path -LiteralPath $TrustPath -PathType Leaf)) { throw 'Copy the public deployment-trust.json from Linux setup first.' }
$trust = Get-Content -LiteralPath $TrustPath -Raw | ConvertFrom-Json
if (-not $trust.releaseKeys -or -not $trust.organizationKeys) { throw 'Both public verification key domains are required.' }
$engine = Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe'
if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) { throw 'Install Docker Desktop and enable its Linux engine first.' }
$folder = Join-Path $config.stateDirectory 'hotfolder'
if (-not (Test-Path -LiteralPath $folder)) { [void](New-Item -ItemType Directory -Path $folder) }
if ((Get-Item -LiteralPath $folder).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Use a regular protected files area.' }
$config.PSObject.Properties.Remove('tools')
$config | Add-Member -MemberType NoteProperty -Name deployment -Value $trust -Force
$execution = [pscustomobject]@{
    enginePath=$engine;engineEndpoint='npipe:////./pipe/dockerDesktopLinuxEngine'
    stateDirectory=(Join-Path $config.stateDirectory 'runtimes');allowFirstUsePull=$true;pullTimeoutSeconds=180
    runtimes=@();tools=@();files=[pscustomobject]@{root=$folder;maxFileBytes=65536;maxEntries=256;extensions=@('txt','md','json','csv')}
}
$config | Add-Member -MemberType NoteProperty -Name execution -Value $execution -Force
$encoding = New-Object Text.UTF8Encoding($false)
$json = $config | ConvertTo-Json -Depth 40 -Compress
if ($encoding.GetByteCount($json) -gt 65536) { throw 'Client configuration exceeds the supported size.' }
Stop-Service OloToolGateClient
try {
    [IO.File]::WriteAllText($configPath,$json,$encoding)
    foreach ($fixture in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures') -File) {
        $target=Join-Path $folder $fixture.Name
        if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $fixture.FullName -Destination $target }
    }
    Start-Service OloToolGateClient
} catch {
    [IO.File]::WriteAllText($configPath,$original,$encoding)
    Start-Service OloToolGateClient
    throw
}
Write-Host "Package executor configured. Deploy to this enrolled device with: deploy.bat -Win -DeviceId $deviceId"
