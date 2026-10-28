# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Enrollment is available from the installed tray without requiring a Chrome extension.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$enrollClient = Join-Path (Split-Path $PSScriptRoot -Parent) 'olo-toolgate-client.exe'
$enrollDeadline = (Get-Date).AddSeconds(35)
do {
    $ErrorActionPreference = 'Continue'
    # A reinstall that resumed its enrollment is already connected; nothing to approve.
    $enrollHealth = & $enrollClient health 2>$null | Out-String
    if ($LASTEXITCODE -eq 0 -and $enrollHealth -match '"state":"(ACTIVE|OFFLINE)"') { exit 0 }
    $enrollOutput = & $enrollClient request-approval 2>&1 | Out-String
    $enrollExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($enrollExitCode -eq 0) { break }
    Start-Sleep -Milliseconds (800 + (Get-Random -Maximum 500))
} while ((Get-Date) -lt $enrollDeadline)
if ($enrollExitCode -ne 0) {
    [System.Windows.Forms.MessageBox]::Show('The client could not start enrollment. Check the tray status and gateway connection, then retry.', 'OLO ToolGate') | Out-Null
    exit 1
}
if ($enrollOutput -match 'fingerprint in your browser: ([a-f0-9]{64})') {
    [System.Windows.Forms.MessageBox]::Show("Approve this device in the ToolGate console. Compare this fingerprint with the enrollment request:`r`n`r`n$($Matches[1])", 'OLO ToolGate enrollment') | Out-Null
}
