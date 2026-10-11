# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Bring up the stack, select a device and issue its agent credential. execute.ps1 runs the test.
param(
    [ValidateSet('any','linux','windows')][string]$Target = 'any',
    [switch]$Win,
    [string]$DeviceId = '',
    [string]$Profiles = '',
    [string]$Python = '',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location $PSScriptRoot
try {
    $Target = Resolve-ExampleTarget $PSBoundParameters $Target $Win.IsPresent
    Import-ExampleEnvironment
    if (-not $env:TOOLGATE_QUICKSTART_IMAGE) { $env:TOOLGATE_QUICKSTART_IMAGE = 'ololab/olo-toolgate-quickstart:dev' }
    if (-not $env:TOOLGATE_DEVICE_IMAGE) { $env:TOOLGATE_DEVICE_IMAGE = 'ololab/olo-toolgate-device:dev' }
    if ($Target -eq 'windows') {
        if (-not $Profiles) { $Profiles = $env:TOOLGATE_WINDOWS_PROFILES }
    }
    if ($Profiles -and -not (Test-Path -LiteralPath $Profiles -PathType Leaf)) {
        throw "Device profiles not found: $Profiles. Run prepare-windows.ps1 on the Windows device to register its public profiles."
    }
    if (-not $Python) {
        . (Join-Path $PSScriptRoot '../../debug/python.ps1')
        $Python = Find-DebugPython
    }
    Invoke-ExampleCommand 'docker' @('compose','version')
    Invoke-ExampleCommand 'docker' @('compose','config','--quiet')
    # An existing .env (or a machine environment variable) wins over .env.example and may pin an old build.
    Write-Host "Quickstart image: $env:TOOLGATE_QUICKSTART_IMAGE"
    Write-Host "Device image: $env:TOOLGATE_DEVICE_IMAGE"
    if ($env:TOOLGATE_QUICKSTART_IMAGE -match ':dev-[0-9a-f]{12}') {
        Write-Host 'This is a pinned development build, not the latest. Set TOOLGATE_QUICKSTART_IMAGE=ololab/olo-toolgate-quickstart:dev in .env to follow dev.' -ForegroundColor Yellow
    }
    if (-not $SkipBuild) {
        # Mutable tags such as `dev` must be re-pulled; a cached local copy keeps the old
        # server and Linux client.
        Write-Host 'Pulling the published Quickstart and device images.'
        Invoke-ImageCommand @('compose','pull','quickstart','linux-device')
        Write-Host 'Installing LangChain in the agent image.'
        Invoke-ImageCommand @('compose','build','--pull','agent')
    } else {
        foreach ($service in @(@('quickstart',$env:TOOLGATE_QUICKSTART_IMAGE), @('linux-device',$env:TOOLGATE_DEVICE_IMAGE))) {
            $ErrorActionPreference = 'Continue'
            & docker image inspect $service[1] *> $null
            $imageExists = $LASTEXITCODE -eq 0
            $ErrorActionPreference = 'Stop'
            if (-not $imageExists) { Invoke-ImageCommand @('compose','pull',$service[0]) }
        }
    }
    # Replace the wrapper even when the image is cached; named volumes retain its identity and files.
    Invoke-ExampleCommand 'docker' @('compose','up','-d','--force-recreate','--wait','--wait-timeout','180','quickstart','linux-device')
    $setupArguments = @('manage.py','setup','--bootstrap-local','--approve-linux-device','--target',$Target)
    if ($DeviceId) { $setupArguments += @('--device-id',$DeviceId) }
    if ($Profiles) { $setupArguments += @('--profiles',$Profiles) }
    Invoke-ExampleCommand $Python $setupArguments
    Write-Host "Console: http://127.0.0.1:$($env:TOOLGATE_HTTP_PORT)/console/"
    $next = if ($Target -eq 'windows') { 'execute.bat -Win' } elseif ($Target -eq 'any') { 'execute.bat' } else { "execute.bat -Target $Target" }
    Write-Host "Deployed. Run the test with: $next (add -Smoke to skip the model)"
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally { Pop-Location }
