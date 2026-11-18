# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param(
    [ValidateSet('start','stop','restart')][string]$Operation='start',
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
$stackOperation=$Operation
if ($ValidateOnly) { $stackOperation='validate' }
& $python "$PSScriptRoot/stack.py" $stackOperation
exit $LASTEXITCODE
