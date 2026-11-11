@echo off
rem Copyright 2026 OLO Labs
rem SPDX-License-Identifier: Apache-2.0
rem ToolGate API login settings. Prefer environment variables to committing passwords.
if not defined TOOLGATE_ADMIN_USERNAME set "TOOLGATE_ADMIN_USERNAME=admin"
rem Set TOOLGATE_ADMIN_PASSWORD here or in your environment. Empty is supported only by password-disabled local Quickstart.
if not defined TOOLGATE_ADMIN_PASSWORD set "TOOLGATE_ADMIN_PASSWORD="
rem Optional independent reviewer API credentials; without these, approve submitted drafts in the console.
if not defined TOOLGATE_REVIEWER1_USERNAME set "TOOLGATE_REVIEWER1_USERNAME=reviewer-1"
if not defined TOOLGATE_REVIEWER2_USERNAME set "TOOLGATE_REVIEWER2_USERNAME=reviewer-2"
powershell -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0configure-debug-agent.ps1" %*
exit /b %errorlevel%
