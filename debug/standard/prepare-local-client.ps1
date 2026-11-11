# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Runs the self-contained native installer; certificate setup belongs to the client.
param([string]$ResultFile = '', [string]$Peer = '')
$ErrorActionPreference = 'Stop'
$prepareRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $ResultFile) { $ResultFile = Join-Path $prepareRoot '.dev/debug/native-prepare-result.json' }
$prepareIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $Peer) { $Peer = $prepareIdentity.User.Value }
if ($Peer -notmatch '^S-1-\d+(?:-\d+)+$') { throw 'Invalid Windows account SID.' }
if (-not ([Security.Principal.WindowsPrincipal]$prepareIdentity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'Windows administrator approval is required to repair the service and configure its local CA.'
    $prepareArguments = @('-NoProfile', '-ExecutionPolicy', 'RemoteSigned', '-File', "`"$PSCommandPath`"", '-ResultFile', "`"$ResultFile`"", '-Peer', $Peer)
    $prepareProcess = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList $prepareArguments -Verb RunAs -WindowStyle Hidden -PassThru
    $prepareElapsed = [Diagnostics.Stopwatch]::StartNew()
    $prepareNextUpdate = 15
    # Wait for this helper, not its long-lived tray descendants or an arbitrary three-minute timer.
    while (-not $prepareProcess.WaitForExit(1000)) {
        if ($prepareElapsed.Elapsed.TotalSeconds -ge $prepareNextUpdate) {
            Write-Host "Waiting for Windows client repair ($([int]$prepareElapsed.Elapsed.TotalSeconds)s). See .dev/debug/native-installer.log."
            $prepareNextUpdate += 15
        }
    }
    if (-not (Test-Path -LiteralPath $ResultFile)) { throw 'Windows repair exited without a result. Inspect .dev/debug/native-installer.log.' }
    $prepareResult = Get-Content -LiteralPath $ResultFile -Raw | ConvertFrom-Json
    if (-not $prepareResult.success) { throw $prepareResult.message }
    exit 0
}
try {
    $prepareInstaller = Join-Path $prepareRoot 'deploy/client-assets/release/olo-toolgate-client-x86_64-pc-windows-gnu.setup.exe'
    if (-not (Test-Path -LiteralPath $prepareInstaller)) { throw 'Build the native debug installer first.' }
    $prepareExpected = (Get-Content -LiteralPath ($prepareInstaller + '.sha256') -Raw).Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $prepareInstaller -Algorithm SHA256).Hash.ToLowerInvariant() -ne $prepareExpected.ToLowerInvariant()) { throw 'Installer checksum mismatch.' }
    $prepareClientConfig = 'C:\ProgramData\OLO\ToolGate\client.json'
    if (Test-Path -LiteralPath $prepareClientConfig) {
        $prepareExisting = Get-Content -LiteralPath $prepareClientConfig -Raw | ConvertFrom-Json
        if ($prepareExisting.serverUrl -ne 'https://localhost:18450') { throw 'This helper only repairs an installation already using the local debug Gateway.' }
    }
    $prepareTrayScript = 'C:\Program Files\OLO\ToolGateSetup\packaging\toolgate-tray.ps1'
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($prepareTrayScript)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    $prepareLog = Join-Path $prepareRoot '.dev/debug/native-installer.log'
    $prepareSetup = Start-Process -FilePath $prepareInstaller -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SERVER=https://localhost:18450', "/PEER=$Peer", "/LOG=`"$prepareLog`"") -WindowStyle Hidden -PassThru
    $prepareSetup.WaitForExit()
    if ($prepareSetup.ExitCode -ne 0) { throw 'Native installer repair failed. Inspect .dev/debug/native-installer.log.' }
    if ($Peer -ne $prepareIdentity.User.Value) {
        # A credential prompt can run the installer as another account; the caller starts its own tray.
        Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" | Where-Object {
            $_.CommandLine -and $_.CommandLine.Contains($prepareTrayScript)
        } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    }
    $prepareConfig = Get-Content -LiteralPath $prepareClientConfig -Raw | ConvertFrom-Json
    if (-not $prepareConfig.caCertificatePath) { throw 'The installer did not configure gateway certificate trust.' }
    $prepareCaCertificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2($prepareConfig.caCertificatePath)
    @{success=$true; gateway='https://localhost:18450'; caThumbprint=$prepareCaCertificate.Thumbprint} | ConvertTo-Json | Set-Content -LiteralPath $ResultFile -Encoding UTF8
} catch {
    @{success=$false; message=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath $ResultFile -Encoding UTF8
    exit 1
}
