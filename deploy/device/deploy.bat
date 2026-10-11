@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
exit /b %ERRORLEVEL%
