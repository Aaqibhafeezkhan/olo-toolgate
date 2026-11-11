@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
rem ToolGate admin API credentials. Set the password here or in the environment; do not commit secrets.
if not defined TOOLGATE_ADMIN_USERNAME set "TOOLGATE_ADMIN_USERNAME=admin"
if not defined TOOLGATE_ADMIN_PASSWORD set "TOOLGATE_ADMIN_PASSWORD="
set "TOOLGATE_TEST_AGENT=debug-mimic-agent"
set "TOOLGATE_TEST_AGENT_GROUP=ReadAndWriteAgentGroup"
call "%~dp0configure-debug-agent.bat" --allocate-agent "%TOOLGATE_TEST_AGENT%" --agent-group "%TOOLGATE_TEST_AGENT_GROUP%"
if errorlevel 1 goto allocationFailed
powershell -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0setup-agent-mimic.ps1" %*
set "mimicExitCode=%errorlevel%"
pause
exit /b %mimicExitCode%
:allocationFailed
echo FAILED: Agent allocation was not applied. Review the configuration request or check the admin API credentials.
pause
exit /b 1
