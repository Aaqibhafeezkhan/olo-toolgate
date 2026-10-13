# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Exercise real WinForms controls without starting a tray or contacting a device.
param([string]$Screenshot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $root 'apps/endpoint-client/packaging/toolgate-status.ps1')
$logo = Join-Path $root 'apps/admin-ui/src/assets/olo.png'
$form = New-ToolGateStatusWindow -LogoPath $logo
$about = New-ToolGateAboutWindow -LogoPath $logo -Version '0.10.0-dev' -Build '42 (8f0822a)'
try {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $running = [pscustomobject]@{ active = @([pscustomobject]@{ name = 'hotfolder.write_text'; startedAtUnixMs = $now - 4000 }); events = @(); logAvailable = $true }
    Update-ToolGateStatusWindow $form 'Service running - Connected' $running $true
    if ($form.Tag.Progress.Style -ne 'Marquee' -or $form.Tag.Command.Text -ne 'Executing: hotfolder.write_text') { throw 'Running state is missing' }
    $running.active[0] | Add-Member NoteProperty progressPercent 45
    Update-ToolGateStatusWindow $form 'Service running - Connected' $running $true
    if ($form.Tag.Progress.Style -ne 'Continuous' -or $form.Tag.Progress.Value -ne 45) { throw 'Measured progress is missing' }
    $idle = [pscustomobject]@{ active = @(); lastCommand = [pscustomobject]@{ name = 'calculator.evaluate'; state = 'SUCCEEDED'; finishedAtUnixMs = $now }; events = @([pscustomobject]@{ name = 'calculator.evaluate'; state = 'SUCCEEDED'; timestampUnixMs = $now }); logAvailable = $true }
    Update-ToolGateStatusWindow $form 'Connected' $idle $true
    if ($form.Tag.Command.Text -ne 'Last command: calculator.evaluate' -or $form.Tag.Logs.Text -notmatch 'SUCCEEDED') { throw 'Last command or log missing' }
    Update-ToolGateStatusWindow $form 'Unavailable' $idle $false
    if ($form.Tag.Command.Text -ne 'Command status unavailable') { throw 'Stale activity presented as current' }
    if (-not $form.Tag.Logo.Image -or $about.Tag.Layout.GetControlFromPosition(0, 1).Text -notmatch '0.10.0-dev' -or $about.Tag.Layout.GetControlFromPosition(0, 1).Text -notmatch 'Build: 42 \(8f0822a\)') { throw 'Branding/version missing' }
    foreach ($window in @($form,$about)) {
        if (-not $window.Tag.BrandIcon -or $window.Icon -ne $window.Tag.BrandIcon -or $window.Icon.Width -ne 32) {
            throw 'OLO window icon missing'
        }
    }
    $healthy = [pscustomobject]@{ state = 'ACTIVE'; ready = $true; successfulCheckIns = 3; failedCheckIns = 0 }
    $off = Get-ToolGateHealthSummary $healthy 'off'
    if ($off.Connected -or $off.Status -ne 'Powered off' -or -not (Get-ToolGateHealthSummary $healthy).Connected) { throw 'Power switch state is missing' }
    $background = @([pscustomobject]@{ serverUrl = 'https://focus.example'; focused = $true; activity = $idle }, [pscustomobject]@{ serverUrl = 'https://other.example'; focused = $false; health = $healthy; activity = $idle })
    Update-ToolGateStatusWindow -Form $form -Detail "Service running`r`nPower: Off" -Activity $idle -Available $true -Connections $background -Power 'off'
    if ($form.Tag.Connections[1].Detail.Text -notmatch '^Powered off') { throw 'Background gateway shown as connected while powered off' }
    Update-ToolGateStatusWindow -Form $form -Detail 'Connected' -Activity $idle -Available $true
    $running.active[0].PSObject.Properties.Remove('progressPercent')
    Update-ToolGateStatusWindow $form "Service running`r`nStatus: Connected`r`nProtected tools are ready." $running $true
    $form.StartPosition = 'Manual'
    $form.Location = New-Object System.Drawing.Point(-4000, -4000)
    $form.Show()
    [System.Windows.Forms.Application]::DoEvents()
    $form.PerformLayout()
    if ($Screenshot) {
        $bitmap = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
        try { $form.DrawToBitmap($bitmap, (New-Object System.Drawing.Rectangle(0, 0, $form.Width, $form.Height))); $bitmap.Save($Screenshot) }
        finally { $bitmap.Dispose() }
    }
    foreach ($path in @('toolgate-status.ps1', 'toolgate-tray.ps1')) {
        $tokens = $null; $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root "apps/endpoint-client/packaging/$path"), [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count) { throw "PowerShell parse failed: $path" }
    }
    Write-Output 'WinForms branding, running/measured/idle/unavailable/powered-off states, activity and About version passed.'
} finally { $form.Close(); $form.Dispose(); $about.Close(); $about.Dispose() }
