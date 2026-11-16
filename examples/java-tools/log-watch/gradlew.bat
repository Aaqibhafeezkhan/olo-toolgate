@echo off
rem Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
call "%~dp0..\gradlew.bat" %*
exit /b %ERRORLEVEL%
