# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Runs in the signed-in user's session; the service owns all credentials.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$trayMutex = New-Object System.Threading.Mutex($false, 'Local\OloToolGateTray')
if (-not $trayMutex.WaitOne(0)) { $trayMutex.Dispose(); exit }
$trayRoot = Split-Path $PSScriptRoot -Parent
$trayClient = Join-Path $trayRoot 'olo-toolgate-client.exe'
$trayConfig = 'C:\ProgramData\OLO\ToolGate\client.json'
$script:trayProcess = $null
$script:trayStarted = $null
$script:trayDetail = 'Checking the ToolGate service...'
$script:trayState = ''
$script:statusWindow = $null
$script:aboutWindow = $null
$script:activityProcess = $null
$script:activityOutput = $null
$script:activityError = $null
$script:activityStarted = $null
$script:activitySnapshot = $null
$script:activityAvailable = $false
$script:connections = @()
$script:connectionsChecked = [DateTime]::MinValue
$trayIndex = 'C:\ProgramData\OLO\ToolGate\gateways.json'
$trayEnrollScript = Join-Path $PSScriptRoot 'toolgate-enroll.ps1'
$script:trustChanged = $false
$script:trustNotified = $false
$script:trustTask = $null
$script:trustClient = $null
$script:trustChecked = [DateTime]::MinValue
$script:enrollAfterSwitch = $null
function Test-ToolGateGatewayAddress([string]$Address) {
    return ($Address -match '^https://[a-zA-Z0-9.\[\]:-]+$') -or ($Address -match '^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$')
}
function Get-ToolGateGatewaySettings {
    try { return [IO.File]::ReadAllText($trayConfig) | ConvertFrom-Json } catch { return $null }
}
function Get-ToolGateKnownGateways {
    try { $known = [IO.File]::ReadAllText($trayIndex) | ConvertFrom-Json } catch { return @() }
    return @($known | Where-Object { $_.serverUrl -and (Test-ToolGateGatewayAddress ([string]$_.serverUrl)) })
}
# The protected client verifies the gateway and its certificate; the tray only asks for elevation.
function Invoke-ToolGateConfigure([string]$Target) {
    if (-not (Test-ToolGateGatewayAddress $Target)) {
        $script:trayDetail = 'Use an https:// gateway URL or a local console address such as http://127.0.0.1:18091.'
        return
    }
    $peer = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    try {
        Start-Process -FilePath $trayClient -Verb RunAs -WindowStyle Hidden -ArgumentList "configure --server `"$Target`" --peer `"$peer`""
        $script:trustChanged = $false
        $script:trustNotified = $false
        $script:enrollAfterSwitch = Get-Date
        $script:trayDetail = "Connecting to $Target..."
    } catch { $script:trayDetail = 'Gateway change was cancelled or could not start.' }
}
function Invoke-ToolGateRepair {
    $settings = Get-ToolGateGatewaySettings
    if (-not $settings) { return }
    Invoke-ToolGateConfigure $(if ($settings.localConsoleUrl) { [string]$settings.localConsoleUrl } else { [string]$settings.serverUrl })
}
# A local gateway recreated with a fresh volume publishes a new CA on its console; offer repair.
function Update-ToolGateTrustCheck([bool]$Ready) {
    if ($Ready) { $script:trustChanged = $false; $script:trustNotified = $false; return }
    if ($script:trustTask) {
        if (-not $script:trustTask.IsCompleted) { return }
        try {
            $settings = Get-ToolGateGatewaySettings
            $trust = $script:trustTask.Result | ConvertFrom-Json
            if ($settings -and $settings.caCertificatePath -and $trust.serverUrl -eq $settings.serverUrl) {
                $published = New-Object Security.Cryptography.X509Certificates.X509Certificate2(,[Text.Encoding]::ASCII.GetBytes([string]$trust.caCertificatePem))
                $installed = New-Object Security.Cryptography.X509Certificates.X509Certificate2([string]$settings.caCertificatePath)
                $script:trustChanged = $published.Thumbprint -ne $installed.Thumbprint
            }
        } catch { }
        finally { $script:trustClient.Dispose(); $script:trustClient = $null; $script:trustTask = $null }
        if ($script:trustChanged -and -not $script:trustNotified) {
            $script:trustNotified = $true
            $trayIcon.ShowBalloonTip(10000, 'ToolGate gateway was recreated', 'Its certificate changed. Click here to reconnect this device.', [System.Windows.Forms.ToolTipIcon]::Warning)
        }
        return
    }
    if (((Get-Date) - $script:trustChecked).TotalSeconds -lt 30) { return }
    $script:trustChecked = Get-Date
    $settings = Get-ToolGateGatewaySettings
    if (-not $settings -or -not ([string]$settings.localConsoleUrl -match '^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$')) { return }
    $script:trustClient = New-Object Net.WebClient
    $script:trustTask = $script:trustClient.DownloadStringTaskAsync(([string]$settings.localConsoleUrl) + '/api/public/v1/clients/local-trust')
}
. (Join-Path $PSScriptRoot 'toolgate-status.ps1')
. (Join-Path $PSScriptRoot 'toolgate-icons.ps1')
$trayLogo = Join-Path $PSScriptRoot 'olo.png'
$trayConnectedIcon = New-ToolGateStatusIcon -LogoPath $trayLogo -Connected $true
$trayOfflineIcon = New-ToolGateStatusIcon -LogoPath $trayLogo -Connected $false
$trayConnectedImage = $trayConnectedIcon.ToBitmap()
$trayOfflineImage = $trayOfflineIcon.ToBitmap()
$trayIcon = New-Object System.Windows.Forms.NotifyIcon
$trayIcon.Icon = $trayOfflineIcon
$trayIcon.Text = 'ToolGate: checking service'
$trayIcon.Visible = $true
$trayMenu = New-Object System.Windows.Forms.ContextMenuStrip
$trayStatus = $trayMenu.Items.Add('Show status')
$trayStatus.add_Click({
    if (-not $script:statusWindow -or $script:statusWindow.IsDisposed) {
        $script:statusWindow = New-ToolGateStatusWindow -LogoPath $trayLogo
    }
    Update-ToolGateStatusWindow -Form $script:statusWindow -Detail $script:trayDetail -Activity $script:activitySnapshot -Available $script:activityAvailable -Connections $script:connections
    $script:statusWindow.Show()
    $script:statusWindow.Activate()
})
$trayConsole = $trayMenu.Items.Add('Open ToolGate console')
$trayEnroll = $trayMenu.Items.Add('Request device approval')
$trayEnroll.add_Click({
    Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile', '-STA', '-ExecutionPolicy', 'RemoteSigned', '-WindowStyle', 'Hidden', '-File', "`"$(Join-Path $PSScriptRoot 'toolgate-enroll.ps1')`"") -WindowStyle Hidden
})
$trayConsole.add_Click({
    $trayServer = (Get-ItemProperty -LiteralPath 'HKLM:\Software\OLO\ToolGate' -ErrorAction SilentlyContinue).ServerUrl
    $trayLocal = Get-ToolGateGatewaySettings
    if ($trayLocal -and $trayLocal.serverUrl -eq $trayServer -and $trayLocal.localConsoleUrl) { $trayServer = $trayLocal.localConsoleUrl }
    elseif ($trayServer -eq 'https://localhost:18450') { $trayServer = 'http://127.0.0.1:18090' }
    if (Test-ToolGateGatewayAddress $trayServer) {
        $trayRoute = if ($script:trayState -in @('UNENROLLED', 'PENDING')) { '/console/#enroll' } else { '/console/' }
        Start-Process ($trayServer.TrimEnd('/') + $trayRoute)
    }
})
# Switching moves focus: the previous gateway stays connected in the background and
# switching back resumes its enrollment without a new approval.
$traySwitch = New-Object System.Windows.Forms.ToolStripMenuItem('Switch gateway')
[void]$trayMenu.Items.Insert(2, $traySwitch)
$trayRepair = New-Object System.Windows.Forms.ToolStripMenuItem('Repair gateway connection')
$trayRepair.add_Click({ Invoke-ToolGateRepair })
[void]$trayMenu.Items.Insert(3, $trayRepair)
$trayMenu.add_Opening({
    $traySwitch.DropDownItems.Clear()
    $trayCurrent = Get-ToolGateGatewaySettings
    foreach ($trayGateway in @(Get-ToolGateKnownGateways)) {
        $trayTarget = if ($trayGateway.consoleUrl) { [string]$trayGateway.consoleUrl } else { [string]$trayGateway.serverUrl }
        $trayLabel = [string]$trayGateway.serverUrl
        $trayLive = @($script:connections | Where-Object { $_.serverUrl -eq $trayGateway.serverUrl })
        if ($trayLive.Count -and $trayLive[0].serverName) { $trayLabel = "$($trayLive[0].serverName) - $trayLabel" }
        if ($trayGateway.consoleUrl) { $trayLabel += "  (console $($trayGateway.consoleUrl))" }
        $trayItem = New-Object System.Windows.Forms.ToolStripMenuItem($trayLabel)
        # Same green/red as the tray icon, per gateway, so background connections are visible too.
        if ($trayLive.Count) { $trayItem.Image = if ((Get-ToolGateHealthSummary $trayLive[0].health).Connected) { $trayConnectedImage } else { $trayOfflineImage } }
        $trayItem.Tag = $trayTarget
        $trayItem.Checked = $trayCurrent -and $trayCurrent.serverUrl -eq $trayGateway.serverUrl
        $trayItem.add_Click({ param($sender) Invoke-ToolGateConfigure ([string]$sender.Tag) })
        [void]$traySwitch.DropDownItems.Add($trayItem)
    }
    $trayOther = New-Object System.Windows.Forms.ToolStripMenuItem('Other gateway...')
    $trayOther.add_Click({
        Add-Type -AssemblyName Microsoft.VisualBasic
        $trayTyped = [Microsoft.VisualBasic.Interaction]::InputBox('Gateway URL, or a local console address such as http://127.0.0.1:18091', 'Switch ToolGate gateway', '')
        if ($trayTyped) { Invoke-ToolGateConfigure $trayTyped.Trim().TrimEnd('/') }
    })
    [void]$traySwitch.DropDownItems.Add($trayOther)
    $trayRepair.Font = if ($script:trustChanged) { New-Object System.Drawing.Font($trayMenu.Font, [System.Drawing.FontStyle]::Bold) } else { $trayMenu.Font }
})
$trayIcon.add_BalloonTipClicked({ if ($script:trustChanged) { Invoke-ToolGateRepair } })
$trayPackets = $trayMenu.Items.Add('View messages sent / received')
$trayPackets.add_Click({
    $trayPacketScript = Join-Path $PSScriptRoot 'toolgate-packets.ps1'
    try {
        Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Verb RunAs -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'RemoteSigned', '-File', "`"$trayPacketScript`"")
    } catch { $script:trayDetail = 'Packet viewer was cancelled or could not start.' }
})
$trayUninstall = $trayMenu.Items.Add('Uninstall ToolGate...')
$trayUninstall.add_Click({
    $trayUninstaller = Join-Path $trayRoot 'unins000.exe'
    try { Start-Process -FilePath $trayUninstaller -Verb RunAs }
    catch { $script:trayDetail = 'Uninstall was cancelled or could not start.' }
})
function Get-ToolGateClientOutput {
    param([string]$Arguments, [string]$Pattern)
    $value = 'Unavailable'
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $trayClient
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = $null
    try {
        $process = [System.Diagnostics.Process]::Start($start)
        if ($process.WaitForExit(2000) -and $process.ExitCode -eq 0) {
            $candidate = $process.StandardOutput.ReadToEnd().Trim()
            if ($candidate -match $Pattern) { $value = $candidate }
        } else { if (-not $process.HasExited) { $process.Kill() } }
    } catch { $value = 'Unavailable' }
    finally { if ($process) { $process.Dispose() } }
    return $value
}
$trayAbout = $trayMenu.Items.Add('About OLO ToolGate')
$trayAbout.add_Click({
    if (-not $script:aboutWindow -or $script:aboutWindow.IsDisposed) {
        $version = Get-ToolGateClientOutput -Arguments 'version' -Pattern '^[0-9]+\.[0-9]+\.[0-9]+[a-zA-Z0-9.+-]*$'
        $build = Get-ToolGateClientOutput -Arguments 'build' -Pattern '^(local|[0-9]+ \([0-9a-f]{1,40}\))$'
        $script:aboutWindow = New-ToolGateAboutWindow -LogoPath $trayLogo -Version $version -Build $build
    }
    $script:aboutWindow.Show()
    $script:aboutWindow.Activate()
})
$trayExit = $trayMenu.Items.Add('Exit tray icon')
$trayExit.add_Click({ [System.Windows.Forms.Application]::ExitThread() })
$trayIcon.ContextMenuStrip = $trayMenu
$trayIcon.add_DoubleClick({ $trayStatus.PerformClick() })
$trayTimer = New-Object System.Windows.Forms.Timer
$trayTimer.Interval = 2000
$trayTimer.add_Tick({
    if (-not (Test-Path -LiteralPath $trayConfig)) {
        [System.Windows.Forms.Application]::ExitThread()
        return
    }
    if ($script:trayProcess) {
        if (-not $script:trayProcess.HasExited) {
            if (((Get-Date) - $script:trayStarted).TotalSeconds -lt 22) { return }
            $script:trayProcess.Kill()
        }
        try {
            $trayHealth = $script:trayProcess.StandardOutput.ReadToEnd() | ConvertFrom-Json
            if ($trayHealth.error -or -not $trayHealth.state) { throw 'Health unavailable' }
            $trayState = [string]$trayHealth.state
            $script:trayState = $trayState
            $traySummary = Get-ToolGateHealthSummary $trayHealth
            $trayStatusText = $traySummary.Status
            $trayGuidance = $traySummary.Guidance
            Update-ToolGateTrustCheck ($trayHealth.ready -and $trayState -eq 'ACTIVE')
            if ($script:trustChanged) { $trayGuidance = 'The local gateway was recreated with a new certificate. Choose Repair gateway connection.' }
            if ($script:enrollAfterSwitch) {
                # Start enrollment once the new gateway configuration is active, never against the old one.
                $traySwitched = (Get-Item -LiteralPath $trayConfig).LastWriteTime -gt $script:enrollAfterSwitch
                if ($traySwitched -and $trayState -eq 'UNENROLLED') {
                    $script:enrollAfterSwitch = $null
                    Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile', '-STA', '-ExecutionPolicy', 'RemoteSigned', '-WindowStyle', 'Hidden', '-File', "`"$trayEnrollScript`"") -WindowStyle Hidden
                } elseif (($traySwitched -and $trayState -ne 'OFFLINE') -or ((Get-Date) - $script:enrollAfterSwitch).TotalSeconds -gt 180) {
                    $script:enrollAfterSwitch = $null
                }
            }
            $script:trayDetail = "Service running`r`nStatus: $trayStatusText`r`n$trayGuidance`r`nSuccessful check-ins: $($trayHealth.successfulCheckIns)`r`nFailed check-ins: $($trayHealth.failedCheckIns)"
            # The icon color always follows the gateway in focus; background gateways show in the menu.
            $trayFocused = @($script:connections | Where-Object { $_.focused })
            $trayName = if ($trayFocused.Count -and $trayFocused[0].serverName) { [string]$trayFocused[0].serverName + ': ' } else { '' }
            $trayTip = 'ToolGate: ' + $trayName + $trayStatusText
            if ($script:connections.Count -gt 1) { $trayTip += " (+$($script:connections.Count - 1))" }
            $trayIcon.Text = if ($trayTip.Length -gt 63) { $trayTip.Substring(0, 63) } else { $trayTip }
            $trayIcon.Icon = if ($traySummary.Connected) { $trayConnectedIcon } else { $trayOfflineIcon }
        } catch {
            $trayService = Get-Service -Name OloToolGateClient -ErrorAction SilentlyContinue
            if ($trayService.Status -eq 'Running') {
                $script:trayDetail = 'ToolGate service is running. Its status is busy or unavailable to this Windows account.'
                $trayIcon.Text = 'ToolGate: service running, status pending'
                $trayIcon.Icon = $trayOfflineIcon
            } else {
                $script:trayDetail = 'ToolGate service is unavailable.'
                $trayIcon.Text = 'ToolGate: service unavailable'
                $trayIcon.Icon = $trayOfflineIcon
            }
        } finally {
            $script:trayProcess.Dispose()
            $script:trayProcess = $null
        }
    }
    $trayStart = New-Object System.Diagnostics.ProcessStartInfo
    $trayStart.FileName = $trayClient
    $trayStart.Arguments = 'health'
    $trayStart.UseShellExecute = $false
    $trayStart.CreateNoWindow = $true
    $trayStart.RedirectStandardOutput = $true
    $trayStart.RedirectStandardError = $true
    try {
        $script:trayProcess = [System.Diagnostics.Process]::Start($trayStart)
        $script:trayStarted = Get-Date
    } catch { $script:trayDetail = 'Could not contact the ToolGate service.'; $trayIcon.Icon = $trayOfflineIcon }
})
# Independent reader remains responsive while a protected command holds execution state.
# One snapshot carries every gateway connection: its health, name and activity, focused first.
$activityTimer = New-Object System.Windows.Forms.Timer
$activityTimer.Interval = 1000
$activityTimer.add_Tick({
    if ($script:activityProcess) {
        if (-not $script:activityProcess.HasExited -and ((Get-Date) - $script:activityStarted).TotalSeconds -gt 6) {
            $script:activityProcess.Kill()
            $script:activityAvailable = $false
        }
        if ($script:activityProcess.HasExited -and $script:activityOutput.IsCompleted -and $script:activityError.IsCompleted) {
            try {
                if ($script:activityProcess.ExitCode -ne 0) { throw 'Activity unavailable' }
                # Windows PowerShell emits a JSON array as one object; assign before wrapping.
                $parsed = ConvertFrom-Json $script:activityOutput.Result
                $script:connections = @($parsed)
                $focused = @($script:connections | Where-Object { $_.focused })
                $script:activitySnapshot = if ($focused.Count) { $focused[0].activity } else { $null }
                $script:activityAvailable = $true
            } catch { $script:activityAvailable = $false }
            finally { $script:activityProcess.Dispose(); $script:activityProcess = $null }
        }
    }
    $windowOpen = $script:statusWindow -and -not $script:statusWindow.IsDisposed
    if ($windowOpen) {
        Update-ToolGateStatusWindow -Form $script:statusWindow -Detail $script:trayDetail -Activity $script:activitySnapshot -Available $script:activityAvailable -Connections $script:connections
    }
    # The Switch gateway menu and tooltip also use the snapshot, so refresh it slowly while the window is closed.
    if (-not $script:activityProcess -and ($windowOpen -or ((Get-Date) - $script:connectionsChecked).TotalSeconds -ge 5)) {
        $script:connectionsChecked = Get-Date
        $start = New-Object System.Diagnostics.ProcessStartInfo
        $start.FileName = $trayClient
        $start.Arguments = 'connections'
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        try {
            $script:activityProcess = [System.Diagnostics.Process]::Start($start)
            $script:activityStarted = Get-Date
            # Drain both pipes immediately; a full log must never block the child.
            $script:activityOutput = $script:activityProcess.StandardOutput.ReadToEndAsync()
            $script:activityError = $script:activityProcess.StandardError.ReadToEndAsync()
        } catch { $script:activityAvailable = $false }
    }
})
try {
    $activityTimer.Start()
    $trayTimer.Start()
    [System.Windows.Forms.Application]::Run()
} finally {
    $activityTimer.Stop()
    $activityTimer.Dispose()
    if ($script:activityProcess) {
        if (-not $script:activityProcess.HasExited) { $script:activityProcess.Kill() }
        $script:activityProcess.Dispose()
    }
    if ($script:statusWindow) { $script:statusWindow.Dispose() }
    if ($script:aboutWindow) { $script:aboutWindow.Dispose() }
    $trayTimer.Stop()
    $trayTimer.Dispose()
    if ($script:trayProcess) {
        if (-not $script:trayProcess.HasExited) { $script:trayProcess.Kill() }
        $script:trayProcess.Dispose()
    }
    $trayIcon.Visible = $false
    $trayIcon.Dispose()
    $trayConnectedIcon.Dispose()
    $trayOfflineIcon.Dispose()
    $trayConnectedImage.Dispose()
    $trayOfflineImage.Dispose()
    $trayMenu.Dispose()
    $trayMutex.ReleaseMutex()
    $trayMutex.Dispose()
}
