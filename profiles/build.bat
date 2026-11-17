@echo off
rem Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
python "%~dp0build.py" %*
exit /b %errorlevel%
