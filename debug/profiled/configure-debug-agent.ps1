# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
$ErrorActionPreference='Stop'
$configurationPython=Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.dev/debug/venv/Scripts/python.exe'
if(-not (Test-Path -LiteralPath $configurationPython)){
    . "$PSScriptRoot/python.ps1"
    $configurationPython=Find-DebugPython
}
& $configurationPython "$PSScriptRoot/configure-debug-agent.py" @Arguments
exit $LASTEXITCODE
