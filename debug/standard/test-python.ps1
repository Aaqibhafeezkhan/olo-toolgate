# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/python.ps1"
$installed = Find-DebugPython
# Exercise the original failure order: Store aliases ahead of a real interpreter.
$found = Find-DebugPython -Candidates @('python', 'python3', 'does-not-exist-toolgate-python', $installed)
if ($found -ne $installed) { throw 'Discovery failed to fall back to the working interpreter.' }
# A real executable that rejects --version must also be skipped without aborting.
$found = Find-DebugPython -Candidates @('whoami.exe', $installed)
if ($found -ne $installed) { throw 'A failed probe prevented fallback.' }
try {
    Find-DebugPython -Candidates @('does-not-exist-toolgate-python')
    throw 'Missing runtime was accepted.'
} catch {
    if ($_.Exception.Message -notlike 'No usable Python 3.11+*') { throw }
}
Write-Host "Python discovery regression tests passed: $installed"
