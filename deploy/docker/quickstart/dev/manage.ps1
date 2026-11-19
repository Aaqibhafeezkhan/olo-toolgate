# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param(
    [ValidateSet('start','stop','restart','validate','provision-runtime','ollama-vault')][string]$Operation='start',
    [switch]$ValidateOnly,[switch]$WithOllama,[switch]$WithoutOllama,[switch]$WithMonitoring,[switch]$WithoutMonitoring
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/../../../../debug/profiled/python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
$arguments=@($(if ($ValidateOnly) {'validate'} else {$Operation}))
if ($WithOllama) {$arguments+='--with-ollama'}
if ($WithoutOllama) {$arguments+='--without-ollama'}
if ($WithMonitoring) {$arguments+='--with-monitoring'}
if ($WithoutMonitoring) {$arguments+='--without-monitoring'}
& $python "$PSScriptRoot/manage.py" @arguments
exit $LASTEXITCODE
