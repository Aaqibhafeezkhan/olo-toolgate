# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param([string]$Bot)
$ErrorActionPreference='Stop'
if (-not $Bot) { throw 'Select an explicit bot configuration' }
. "$PSScriptRoot/../debug/profiled/python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
& $python "$PSScriptRoot/quickstart.py" --bot $Bot @args
exit $LASTEXITCODE
