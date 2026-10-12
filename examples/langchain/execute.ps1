# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Run the LangChain test against the device that deploy.ps1 selected for this target.
param(
    [ValidateSet('tickets','operations','inventory')][string]$UseCase = 'tickets',
    [ValidateSet('any','linux','windows')][string]$Target = 'any',
    [switch]$Win,
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location $PSScriptRoot
try {
    $Target = Resolve-ExampleTarget $PSBoundParameters $Target $Win.IsPresent
    Import-ExampleEnvironment
    $deploy = if ($Target -eq 'windows') { 'deploy.bat -Win' } elseif ($Target -eq 'any') { 'deploy.bat' } else { "deploy.bat -Target $Target" }
    $descriptorPath = Join-Path $PSScriptRoot ".state/agents/$Target.json"
    if (-not (Test-Path -LiteralPath $descriptorPath -PathType Leaf)) {
        throw "No deployment for target '$Target'. Run $deploy first."
    }
    $descriptor = Get-Content -LiteralPath $descriptorPath -Raw | ConvertFrom-Json
    if ([long]$descriptor.expiresAtUnixMs -le [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) {
        throw "The agent credential for target '$Target' has expired. Run $deploy again."
    }
    if (-not $Smoke -and -not $env:OPENROUTER_API_KEY) {
        throw 'Set OPENROUTER_API_KEY in examples/langchain/.env (https://openrouter.ai/settings/keys). The free model still needs a key. Use execute.bat -Smoke to test real device operations without a model key.'
    }
    # Restarts a stopped stack without rebuilding; a running stack is left as is.
    Invoke-ExampleCommand 'docker' @('compose','up','-d','--no-build','--wait','--wait-timeout','180','quickstart','linux-device')
    . (Join-Path $PSScriptRoot '../../debug/python.ps1')
    $Python = Find-DebugPython
    Invoke-ExampleCommand $Python @('manage.py','verify-deployment','--target',$Target)
    $agentArguments = @('compose','run','--rm','-T','agent','--target',$Target,'--use-case',$UseCase)
    if ($Smoke) { $agentArguments += '--smoke' }
    Invoke-ExampleCommand 'docker' $agentArguments
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally { Pop-Location }
