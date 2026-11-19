# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param([ValidateSet('configure','start','stop')][string]$Operation='start')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/../python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
& $python "$PSScriptRoot/manage.py" $Operation
exit $LASTEXITCODE
