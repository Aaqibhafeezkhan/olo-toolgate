@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
wsl.exe --cd "%~dp0." -- bash ./windows-launch.sh start %*
exit /b %errorlevel%
