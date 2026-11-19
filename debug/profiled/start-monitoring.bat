@echo off
rem Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0observability\manage.ps1" start %*
exit /b %errorlevel%
