@echo off
REM Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
REM Stop the selected deployment, preserving containers, volumes and state/data.
python "%~dp0manage.py" stop %*
exit /b %errorlevel%
