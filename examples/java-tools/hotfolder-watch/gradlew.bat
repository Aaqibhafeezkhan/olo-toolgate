@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
rem Runs the shared build one directory up, so `toolgate dev`, `check` and `pack` work from this directory.
call "%~dp0..\gradlew.bat" %*
exit /b %ERRORLEVEL%
