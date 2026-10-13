# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Presentation only: activity is obtained through the authenticated service pipe.
. (Join-Path $PSScriptRoot 'toolgate-icons.ps1')
function New-ToolGateWindow {
    param([string]$Title, [string]$LogoPath)
    $form = New-Object System.Windows.Forms.Form
    $form.Text = $Title
    $form.ClientSize = New-Object System.Drawing.Size(620, 480)
    $form.MinimumSize = New-Object System.Drawing.Size(540, 500)
    $form.StartPosition = 'CenterScreen'
    $form.AutoScaleMode = 'Dpi'
    $form.Font = New-Object System.Drawing.Font('Segoe UI', 10)
    $form.BackColor = [System.Drawing.Color]::White
    $brandIcon = $null
    if (Test-Path -LiteralPath $LogoPath) {
        $brandIcon = New-ToolGateBrandIcon -LogoPath $LogoPath
        $form.Icon = $brandIcon
    }
    $layout = New-Object System.Windows.Forms.TableLayoutPanel
    $layout.Dock = 'Fill'
    $layout.Padding = New-Object System.Windows.Forms.Padding(20)
    $layout.ColumnCount = 1
    $layout.RowCount = 2
    $layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute', 90))) | Out-Null
    $layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Percent', 100))) | Out-Null
    $header = New-Object System.Windows.Forms.TableLayoutPanel
    $header.Dock = 'Fill'
    $header.ColumnCount = 2
    $header.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle('Absolute', 115))) | Out-Null
    $header.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle('Percent', 100))) | Out-Null
    $logo = New-Object System.Windows.Forms.PictureBox
    $logo.Dock = 'Fill'
    $logo.SizeMode = 'Zoom'
    if (Test-Path -LiteralPath $LogoPath) { $logo.Image = [System.Drawing.Image]::FromFile($LogoPath) }
    $heading = New-Object System.Windows.Forms.Label
    $heading.Text = "OLO ToolGate`r`nDevice client"
    $heading.Font = New-Object System.Drawing.Font('Segoe UI', 17, ([System.Drawing.FontStyle]::Bold))
    $heading.Dock = 'Fill'
    $heading.TextAlign = 'MiddleLeft'
    $header.Controls.Add($logo, 0, 0)
    $header.Controls.Add($heading, 1, 0)
    $layout.Controls.Add($header, 0, 0)
    $form.Controls.Add($layout)
    $form.add_FormClosed({
        if ($this.Tag.Logo.Image) { $this.Tag.Logo.Image.Dispose() }
        if ($this.Tag.BrandIcon) { $this.Tag.BrandIcon.Dispose() }
    })
    $form.Tag = @{ Layout = $layout; Logo = $logo; BrandIcon = $brandIcon }
    return $form
}
# Power is the owner's local switch (olo-toolgate-client power on|off); off overrides gateway state.
function Get-ToolGateHealthSummary {
    param($Health, [string]$Power = 'on')
    if ($Power -eq 'off') {
        return @{ Status = 'Powered off'; Guidance = 'You switched this device off. Choose Power on to reconnect it; no approval is needed.'; Connected = $false }
    }
    $state = [string]$Health.state
    $status = switch ($state) {
        'UNENROLLED' { 'Enrollment required' }
        'PENDING' { 'Waiting for enrollment approval' }
        'REVOKED' { 'Enrollment revoked' }
        'OFFLINE' { 'Offline - waiting for gateway' }
        'ACTIVE' { if ($Health.ready) { 'Connected' } else { 'Waiting for gateway check-in' } }
        default { 'Checking connection' }
    }
    $guidance = switch ($state) {
        'UNENROLLED' { 'Choose Enroll this device from the tray menu, then approve it in the console.' }
        'PENDING' { 'Approve the enrollment code and fingerprint on Enroll Device.' }
        'REVOKED' { 'Contact your administrator to enroll this device again.' }
        default { if ($Health.ready) { 'Protected tools are ready.' } else { 'Protected tools will be ready after enrollment and a successful gateway check-in.' } }
    }
    return @{ Status = $status; Guidance = $guidance; Connected = ($state -eq 'ACTIVE' -and [bool]$Health.ready) }
}
function Get-ToolGateConnectionLabel {
    param($Connection)
    $name = if ($Connection.serverName) { [string]$Connection.serverName } else { 'Gateway' }
    if ($Connection.serverUrl) { return "$name - $($Connection.serverUrl)" }
    return $name
}
function New-ToolGateStatusWindow {
    param([string]$LogoPath)
    $form = New-ToolGateWindow -Title 'OLO ToolGate - Device status' -LogoPath $LogoPath
    $tabs = New-Object System.Windows.Forms.TabControl
    $tabs.Dock = 'Fill'
    $form.Tag.Layout.Controls.Add($tabs, 0, 1)
    $form.Tag.Tabs = $tabs
    $form.Tag.Connections = @()
    $form.Tag.ConnectionKey = $null
    return $form
}
# Every tab names the gateway it belongs to, so two connections are never confused.
function New-ToolGateServerHeader {
    $header = New-Object System.Windows.Forms.Label
    $header.Dock = 'Top'
    $header.Height = 28
    $header.AutoEllipsis = $true
    $header.Padding = New-Object System.Windows.Forms.Padding(12, 6, 12, 0)
    $header.Font = New-Object System.Drawing.Font('Segoe UI', 10, ([System.Drawing.FontStyle]::Bold))
    return $header
}
function New-ToolGateConnectionPages {
    param($Tabs)
    $status = New-Object System.Windows.Forms.TabPage('Status')
    $activity = New-Object System.Windows.Forms.TabPage('Activity log')
    $body = New-Object System.Windows.Forms.TableLayoutPanel
    $body.Dock = 'Fill'
    $body.Padding = New-Object System.Windows.Forms.Padding(12)
    $body.ColumnCount = 1
    $body.RowCount = 5
    foreach ($height in @(110, 60, 28, 40)) { $body.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute', $height))) | Out-Null }
    $body.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Percent', 100))) | Out-Null
    $detail = New-Object System.Windows.Forms.Label
    $detail.Dock = 'Fill'
    $detail.AutoEllipsis = $true
    $command = New-Object System.Windows.Forms.Label
    $command.Dock = 'Fill'
    $command.AutoEllipsis = $true
    $command.Font = New-Object System.Drawing.Font('Segoe UI', 11, ([System.Drawing.FontStyle]::Bold))
    $progress = New-Object System.Windows.Forms.ProgressBar
    $progress.Dock = 'Fill'
    $progress.MarqueeAnimationSpeed = 30
    $caption = New-Object System.Windows.Forms.Label
    $caption.Dock = 'Fill'
    $caption.AutoEllipsis = $true
    $close = New-Object System.Windows.Forms.Button
    $close.Text = 'Close'
    $close.Anchor = 'Bottom,Right'
    $close.AutoSize = $true
    $close.DialogResult = 'Cancel'
    $close.add_Click({ $this.FindForm().Close() })
    $body.Controls.Add($detail, 0, 0)
    $body.Controls.Add($command, 0, 1)
    $body.Controls.Add($progress, 0, 2)
    $body.Controls.Add($caption, 0, 3)
    $body.Controls.Add($close, 0, 4)
    $statusHeader = New-ToolGateServerHeader
    $status.Controls.Add($body)
    $status.Controls.Add($statusHeader)
    $logs = New-Object System.Windows.Forms.TextBox
    $logs.Multiline = $true
    $logs.ReadOnly = $true
    $logs.ScrollBars = 'Both'
    $logs.WordWrap = $false
    $logs.Dock = 'Fill'
    $logs.Font = New-Object System.Drawing.Font('Consolas', 9)
    $logs.AccessibleName = 'Recent device activity log'
    $activityHeader = New-ToolGateServerHeader
    $activity.Controls.Add($logs)
    $activity.Controls.Add($activityHeader)
    return @{ StatusPage = $status; ActivityPage = $activity; StatusHeader = $statusHeader; ActivityHeader = $activityHeader; Detail = $detail; Command = $command; Progress = $progress; Caption = $caption; Logs = $logs; Close = $close }
}
# One Status and one Activity log tab per gateway connection, focused gateway first.
function Sync-ToolGateConnectionPages {
    param($Form, [object[]]$Connections)
    $ui = $Form.Tag
    $key = (@($Connections | ForEach-Object { [string]$_.serverUrl }) -join '|')
    if ($ui.ConnectionKey -eq $key) { return }
    $selected = $ui.Tabs.SelectedIndex
    foreach ($page in @($ui.Tabs.TabPages)) { $page.Dispose() }
    $ui.Tabs.TabPages.Clear()
    $pages = @(foreach ($connection in $Connections) { New-ToolGateConnectionPages -Tabs $ui.Tabs })
    foreach ($entry in $pages) { $ui.Tabs.TabPages.Add($entry.StatusPage) }
    foreach ($entry in $pages) { $ui.Tabs.TabPages.Add($entry.ActivityPage) }
    $ui.Connections = $pages
    $ui.ConnectionKey = $key
    if ($selected -ge 0 -and $selected -lt $ui.Tabs.TabCount) { $ui.Tabs.SelectedIndex = $selected }
    # The focused connection keeps the original single-gateway control names.
    $ui.Detail = $pages[0].Detail
    $ui.Command = $pages[0].Command
    $ui.Progress = $pages[0].Progress
    $ui.Caption = $pages[0].Caption
    $ui.Logs = $pages[0].Logs
    $Form.CancelButton = $pages[0].Close
}
function Update-ToolGateStatusWindow {
    param($Form, [string]$Detail, $Activity, [bool]$Available, [object[]]$Connections = @(), [string]$Power = 'on')
    if (-not $Connections -or $Connections.Count -eq 0) {
        $Connections = @([pscustomobject]@{ serverUrl = ''; focused = $true; activity = $Activity })
    }
    Sync-ToolGateConnectionPages -Form $Form -Connections $Connections
    $multiple = $Connections.Count -gt 1
    for ($index = 0; $index -lt $Connections.Count; $index++) {
        $connection = $Connections[$index]
        $page = $Form.Tag.Connections[$index]
        $label = Get-ToolGateConnectionLabel $connection
        if ($connection.focused -and $multiple) { $label += '  (in focus)' }
        $page.StatusHeader.Text = $label
        $page.ActivityHeader.Text = $label
        $short = if ($connection.serverName) { [string]$connection.serverName } elseif ($connection.serverUrl) { ([string]$connection.serverUrl) -replace '^https?://', '' } else { '' }
        $page.StatusPage.Text = if ($multiple -and $short) { "Status: $short" } else { 'Status' }
        $page.ActivityPage.Text = if ($multiple -and $short) { "Activity log: $short" } else { 'Activity log' }
        if ($connection.focused) {
            $text = $Detail
        } elseif ($connection.health) {
            $summary = Get-ToolGateHealthSummary $connection.health $Power
            $text = "$(if ($Power -eq 'off') { 'Powered off' } else { 'Connected in the background' })`r`nStatus: $($summary.Status)`r`n$($summary.Guidance)`r`nSuccessful check-ins: $($connection.health.successfulCheckIns)`r`nFailed check-ins: $($connection.health.failedCheckIns)"
        } else {
            $text = 'Waiting for the device service.'
        }
        $activity = if ($null -ne $connection.activity) { $connection.activity } else { $Activity }
        Update-ToolGateConnectionView -Page $page -Detail $text -Activity $activity -Available $Available
    }
}
function Update-ToolGateConnectionView {
    param($Page, [string]$Detail, $Activity, [bool]$Available)
    $ui = $Page
    $ui.Detail.Text = $Detail
    $ui.Progress.Visible = $false
    if (-not $Available) {
        $ui.Command.Text = 'Command status unavailable'
        $ui.Caption.Text = 'Waiting for the device service. Previously received activity may be stale.'
        return
    }
    $running = @($Activity.active)
    if ($running.Count -gt 0) {
        $current = $running[0]
        $ui.Command.Text = 'Executing: ' + $current.name
        if ($running.Count -gt 1) { $ui.Command.Text += " (+$($running.Count - 1) more)" }
        $ui.Progress.Visible = $true
        $elapsed = [Math]::Max(0, [Math]::Floor(([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $current.startedAtUnixMs) / 1000))
        if ($null -ne $current.progressPercent) {
            $ui.Progress.Style = 'Continuous'
            $ui.Progress.Value = [Math]::Max(0, [Math]::Min(100, [int]$current.progressPercent))
            $ui.Caption.Text = "$($ui.Progress.Value)% complete - $elapsed seconds elapsed"
        } else {
            $ui.Progress.Style = 'Marquee'
            $ui.Caption.Text = "In progress - $elapsed seconds elapsed"
        }
    } elseif ($Activity.lastCommand) {
        $ui.Command.Text = 'Last command: ' + $Activity.lastCommand.name
        $finished = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Activity.lastCommand.finishedAtUnixMs).LocalDateTime
        $ui.Caption.Text = "$($Activity.lastCommand.state) - $($finished.ToString('g'))"
    } else {
        $ui.Command.Text = 'No command has executed yet'
        $ui.Caption.Text = 'The device is idle.'
    }
    $lines = @('Recent device activity (latest 100 events; arguments and secrets excluded)', '')
    if (-not $Activity.logAvailable) { $lines += 'Device log could not be saved. Contact your administrator.' }
    foreach ($entry in $Activity.events) {
        $when = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$entry.timestampUnixMs).LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss')
        $lines += "$when  $($entry.state)  $($entry.name)"
    }
    # Rewriting identical text resets the reader's scroll position every second.
    $text = $lines -join "`r`n"
    if ($ui.Logs.Text -ne $text) { $ui.Logs.Text = $text }
}
function New-ToolGateAboutWindow {
    param([string]$LogoPath, [string]$Version, [string]$Build = 'Unavailable')
    $form = New-ToolGateWindow -Title 'About OLO ToolGate' -LogoPath $LogoPath
    $text = New-Object System.Windows.Forms.Label
    $text.Dock = 'Fill'
    $text.Text = "OLO ToolGate Client`r`nVersion: $Version`r`nBuild: $Build`r`n`r`nOLO Labs`r`nEnterprise tools and device access`r`n`r`nCopyright 2026 OLO Labs`r`nLicensed under Apache-2.0"
    $form.Tag.Layout.Controls.Add($text, 0, 1)
    return $form
}
