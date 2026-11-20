@echo off
rem Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
rem External credential replacement only; preserves Admin and all container processes.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0manage.ps1" provision-runtime
exit /b %errorlevel%
