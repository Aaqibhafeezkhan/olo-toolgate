# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Inno Setup may register unins001.exe (or a later number) after reinstalling.
function Resolve-ToolGateUninstaller([string]$InstalledDirectory, [object[]]$Registrations) {
    $installedRoot = [IO.Path]::GetFullPath($InstalledDirectory).TrimEnd('\')
    foreach ($registration in $Registrations) {
        if (-not $registration.UninstallString) { continue }
        $command = ([string]$registration.UninstallString).Trim()
        if ($command -notmatch '^"([^"]+)"$') { continue }
        $uninstaller = [IO.Path]::GetFullPath($Matches[1])
        if ([IO.Path]::GetDirectoryName($uninstaller) -ne $installedRoot -or [IO.Path]::GetFileName($uninstaller) -notmatch '^unins\d+\.exe$') { continue }
        if (Test-Path -LiteralPath $uninstaller -PathType Leaf) { return $uninstaller }
    }
    throw 'The registered ToolGate uninstaller is missing. Repair the client installation, then retry uninstall.'
}
function Get-ToolGateUninstaller([string]$InstalledDirectory) {
    $registrations = @(
        Get-ItemProperty -LiteralPath 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\OLO.ToolGate.Client_is1' -ErrorAction SilentlyContinue
        Get-ItemProperty -LiteralPath 'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\OLO.ToolGate.Client_is1' -ErrorAction SilentlyContinue
    )
    Resolve-ToolGateUninstaller $InstalledDirectory $registrations
}
