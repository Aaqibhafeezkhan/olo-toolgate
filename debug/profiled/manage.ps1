# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
param(
    [ValidateSet('start','stop','restart','provision-runtime')][string]$Operation='start',
    [switch]$ValidateOnly,
    [switch]$WithOllama,
    [switch]$WithoutOllama,
    [switch]$WithMonitoring,
    [switch]$WithoutMonitoring
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/python.ps1"
$env:PYTHONUTF8='1'
$python=Find-DebugPython -InstallIfMissing
if ($WithOllama -and $WithoutOllama) { throw 'Choose either -WithOllama or -WithoutOllama.' }
if ($WithMonitoring -and $WithoutMonitoring) { throw 'Choose either -WithMonitoring or -WithoutMonitoring.' }
if ($Operation -eq 'start' -and -not $ValidateOnly -and -not $WithOllama -and -not $WithoutOllama) {
    Write-Host 'Local model provider:'
    Write-Host '  1. Install/reuse Ollama + Qwen3 0.6B, with all NVIDIA GPUs (large first download).'
    Write-Host '  2. Use another provider / keep current provider settings (default).'
    do { $choice=Read-Host 'Choose 1 or 2 [2]' } while ($choice -notin @('','1','2'))
    $WithOllama=($choice -eq '1')
}
if ($Operation -eq 'start' -and -not $ValidateOnly -and -not $WithMonitoring -and -not $WithoutMonitoring) {
    Write-Host 'Workflow observability collector:'
    Write-Host '  1. Create/reuse the collector (configured Langfuse, LangSmith, Phoenix; local-only without keys).'
    Write-Host '  2. Keep current observability settings / do not install (default).'
    do { $monitoringChoice=Read-Host 'Choose 1 or 2 [2]' } while ($monitoringChoice -notin @('','1','2'))
    $WithMonitoring=($monitoringChoice -eq '1')
}
$startupClock=$null
if ($Operation -eq 'start' -and -not $ValidateOnly) {
    $startupClock=[System.Diagnostics.Stopwatch]::StartNew()
    Write-Host ('Startup started (inputs finalized): {0}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))
}
try {
    if ($WithOllama -and -not $ValidateOnly -and $Operation -ne 'stop') {
        & $python "$PSScriptRoot/ollama.py" configure
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    if ($WithMonitoring -and -not $ValidateOnly -and $Operation -ne 'stop') {
        & $python "$PSScriptRoot/observability/manage.py" configure
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    $stackOperation=$Operation
    if ($ValidateOnly) { $stackOperation='validate' }
    & $python "$PSScriptRoot/stack.py" $stackOperation
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($WithOllama -and -not $ValidateOnly) {
        & $python "$PSScriptRoot/ollama.py" $(if ($Operation -eq 'stop') { 'stop' } else { 'start' })
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    if ($WithMonitoring -and -not $ValidateOnly) {
        & $python "$PSScriptRoot/observability/manage.py" $(if ($Operation -eq 'stop') { 'stop' } else { 'start' })
    }
    exit $LASTEXITCODE
} finally {
    if ($null -ne $startupClock) {
        $startupClock.Stop()
        Write-Host ('Startup ended: {0}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))
        Write-Host ('Total time after user input: {0:0.000} seconds' -f $startupClock.Elapsed.TotalSeconds)
    }
}
