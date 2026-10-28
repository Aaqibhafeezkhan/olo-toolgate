@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
setlocal

rem Always check this repository, even when invoked from another directory.
pushd "%~dp0"
if errorlevel 1 exit /b 1

if defined TOOLGATE_CI_PYTHON (
    set "CI_PYTHON=%TOOLGATE_CI_PYTHON%"
    goto python_ready
)
if exist "%~dp0.venv\Scripts\python.exe" (
    set "CI_PYTHON=%~dp0.venv\Scripts\python.exe"
    goto python_ready
)
where py >nul 2>&1
if not errorlevel 1 (
    set "CI_PYTHON=py"
    goto python_ready
)
set "CI_PYTHON=python"

:python_ready
"%CI_PYTHON%" -c "import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python 3.12 or newer is required.
    echo Install Python or set TOOLGATE_CI_PYTHON to its executable path.
    set "CI_RESULT=1"
    goto finish
)
"%CI_PYTHON%" -c "import yaml" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Local CI validation dependencies are missing. Run:
    echo   "%CI_PYTHON%" -m pip install -r tools/requirements.txt
    set "CI_RESULT=1"
    goto finish
)

rem Keep commands and coverage policy in the workflow-backed Python runner.
rem With no arguments, request ALL jobs; coverage gaps must remain nonzero.
if "%~1"=="" (
    "%CI_PYTHON%" tools\ci\local.py run
) else (
    "%CI_PYTHON%" tools\ci\local.py %*
)
set "CI_RESULT=%ERRORLEVEL%"
if "%CI_RESULT%"=="2" echo INCOMPLETE: Some CI jobs require hosted runners. See build\local-ci\report.json.
if "%CI_RESULT%"=="1" echo FAILED: Local CI checks or prerequisites failed. See the output above.

:finish
popd
endlocal & exit /b %CI_RESULT%
