@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
rem Prompts for Ollama or another provider; automation may pass -WithOllama or -WithoutOllama.
rem Also prompts for the collector; automation may pass -WithMonitoring or -WithoutMonitoring.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0manage.ps1" start %*
set "result=%errorlevel%"
if not "%result%"=="0" pause
exit /b %result%
