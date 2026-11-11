# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
function Find-DebugPython {
    param([string[]]$Candidates = @(
        "$env:USERPROFILE/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe",
        'python', 'python3'
    ))
    foreach ($candidate in $Candidates) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if (-not $command) { continue }
        $path = $command.Source
        # Store aliases can emit stderr or open the Store instead of running Python.
        if ($path -match '[\\/]Microsoft[\\/]WindowsApps[\\/]') { continue }
        $probe = New-Object System.Diagnostics.Process
        $probe.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
        $probe.StartInfo.FileName = $path
        $probe.StartInfo.Arguments = '--version'
        $probe.StartInfo.UseShellExecute = $false
        $probe.StartInfo.CreateNoWindow = $true
        $probe.StartInfo.RedirectStandardOutput = $true
        $probe.StartInfo.RedirectStandardError = $true
        try {
            [void]$probe.Start()
            if (-not $probe.WaitForExit(5000)) { $probe.Kill(); continue }
            $output = $probe.StandardOutput.ReadToEnd() + $probe.StandardError.ReadToEnd()
            if ($probe.ExitCode -eq 0 -and $output -match 'Python (\d+\.\d+\.\d+)') {
                if ([version]$Matches[1] -ge [version]'3.11.0') { return $path }
            }
        } catch {
            # A broken installation must not prevent trying the next candidate.
        } finally { $probe.Dispose() }
    }
    throw 'No usable Python 3.11+ found. Install Python from python.org and add it to PATH, then retry.'
}
