# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../apps/endpoint-client/packaging/toolgate-uninstall.ps1')
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('toolgate-uninstaller-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $old = Join-Path $testRoot 'unins000.exe'
    $active = Join-Path $testRoot 'unins001.exe'
    New-Item -ItemType File -Path $old,$active | Out-Null
    $registered = [pscustomobject]@{ UninstallString = '"' + $active + '"' }
    if ((Resolve-ToolGateUninstaller $testRoot @($registered)) -ne $active) { throw 'Must launch the active registration after reinstall' }
    foreach ($invalid in @(
        [pscustomobject]@{ UninstallString = '"' + (Join-Path $testRoot 'unins002.exe') + '"' },
        [pscustomobject]@{ UninstallString = '"C:\Windows\System32\cmd.exe"' },
        [pscustomobject]@{ UninstallString = '"' + $active + '" & arbitrary-command' },
        [pscustomobject]@{ UninstallString = '' }
    )) {
        $rejected = $false
        try { Resolve-ToolGateUninstaller $testRoot @($invalid) | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'An invalid or missing uninstall registration must fail visibly' }
    }
    Write-Output 'PASS registered unins001 selection, stale files, missing registration and path/argument rejection'
} finally {
    # Only the two fixture files and our empty temporary directory are removed.
    Remove-Item -LiteralPath $old,$active -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $testRoot
}
