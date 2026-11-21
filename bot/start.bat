@echo off
rem Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
call "%~dp0..\deploy\docker\quickstart\dev\start.bat" %*
exit /b %errorlevel%
