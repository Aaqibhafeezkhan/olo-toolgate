# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param(
    [ValidateSet('start','stop','restart')][string]$Operation='start',
    [switch]$ValidateOnly,
    [switch]$WithOllama,
    [switch]$WithoutOllama
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
if ($WithOllama -and $WithoutOllama) { throw 'Choose either -WithOllama or -WithoutOllama.' }
if ($Operation -eq 'start' -and -not $ValidateOnly -and -not $WithOllama -and -not $WithoutOllama) {
    Write-Host 'Local model provider:'
    Write-Host '  1. Install/reuse Ollama + Qwen3 0.6B, with all NVIDIA GPUs (large first download).'
    Write-Host '  2. Use another provider / keep current provider settings (default).'
    do { $choice=Read-Host 'Choose 1 or 2 [2]' } while ($choice -notin @('','1','2'))
    $WithOllama=($choice -eq '1')
}
if ($WithOllama -and -not $ValidateOnly -and $Operation -ne 'stop') {
    & $python "$PSScriptRoot/ollama.py" configure
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$stackOperation=$Operation
if ($ValidateOnly) { $stackOperation='validate' }
& $python "$PSScriptRoot/stack.py" $stackOperation
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($WithOllama -and -not $ValidateOnly) {
    & $python "$PSScriptRoot/ollama.py" $(if ($Operation -eq 'stop') { 'stop' } else { 'start' })
}
exit $LASTEXITCODE
