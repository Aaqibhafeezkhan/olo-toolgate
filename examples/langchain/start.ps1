# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# One-shot compatibility wrapper: deploy.ps1, then execute.ps1 with the same target.
param(
    [ValidateSet('tickets','operations','inventory')][string]$UseCase = 'tickets',
    [ValidateSet('any','linux','windows')][string]$Target = 'any',
    [switch]$Win,
    [string]$DeviceId = '',
    [string]$Python = '',
    [switch]$Smoke,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
try {
    $Target = Resolve-ExampleTarget $PSBoundParameters $Target $Win.IsPresent
    Push-Location $PSScriptRoot
    try { Import-ExampleEnvironment } finally { Pop-Location }
    if (-not $Smoke -and -not $env:OPENROUTER_API_KEY) {
        throw 'Set OPENROUTER_API_KEY in examples/langchain/.env (https://openrouter.ai/settings/keys). The free model still needs a key. Use -Smoke to test real device operations without a model key.'
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
$deploy = @{ Target = $Target; DeviceId = $DeviceId; Python = $Python; SkipBuild = $SkipBuild }
& (Join-Path $PSScriptRoot 'deploy.ps1') @deploy
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$execute = @{ Target = $Target; UseCase = $UseCase; Smoke = $Smoke }
& (Join-Path $PSScriptRoot 'execute.ps1') @execute
exit $LASTEXITCODE
