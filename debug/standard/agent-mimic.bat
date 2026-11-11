@echo off
powershell -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0agent-mimic.ps1" %*
if errorlevel 1 pause
