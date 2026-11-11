# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Keep visible debug output in a separate timestamped file for each command.
function Start-DebugConsoleLog {
    param([string]$Name)
    if ($Name -notmatch '^[a-z][a-z0-9-]+$') { throw 'Invalid debug log name.' }
    $consoleLogRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $consoleLogDirectory = Join-Path $consoleLogRoot '.dev/debug/logs'
    [void](New-Item -ItemType Directory -Path $consoleLogDirectory -Force)
    $consoleLogStamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss_fff'
    $consoleLogPath = Join-Path $consoleLogDirectory ("$Name-$consoleLogStamp-$PID.log")
    Start-Transcript -LiteralPath $consoleLogPath -IncludeInvocationHeader | Out-Null
    Write-Host "Console log: $consoleLogPath"
    return $consoleLogPath
}

function Invoke-LoggedDebugCommand {
    param([string]$FilePath, [string[]]$Arguments)
    $consoleCommand = Get-Command -Name $FilePath -CommandType Application -ErrorAction Stop | Select-Object -First 1
    # Native stderr can contain normal Docker progress. Capture both streams as plain
    # console text and let the caller decide success using the actual process exit code.
    $ErrorActionPreference = 'Continue'
    & $consoleCommand.Source @Arguments 2>&1 | ForEach-Object { Write-Host $_.ToString() }
    return $LASTEXITCODE
}
