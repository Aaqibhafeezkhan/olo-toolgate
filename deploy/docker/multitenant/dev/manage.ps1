# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param([ValidateSet('start','stop','status','validate','build-runtime')][string]$Operation='validate')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/../../../../debug/profiled/python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
& $python "$PSScriptRoot/manage.py" $Operation @args
exit $LASTEXITCODE
