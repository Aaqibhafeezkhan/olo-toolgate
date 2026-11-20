@echo off
REM Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
REM Destructive: removes WorkflowStack deployments, volumes and generated state/data.
python "%~dp0manage.py" destroy %*
exit /b %errorlevel%
