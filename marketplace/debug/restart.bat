@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0manage.ps1" restart %*
set "result=%errorlevel%"
if not "%result%"=="0" pause
exit /b %result%
