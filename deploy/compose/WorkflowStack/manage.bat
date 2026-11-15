@echo off
REM Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
python "%~dp0manage.py" %*
exit /b %errorlevel%
