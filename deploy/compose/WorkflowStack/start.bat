@echo off
REM Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
python "%~dp0manage.py" start %*
exit /b %errorlevel%
