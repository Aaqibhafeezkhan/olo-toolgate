@echo off
setlocal
cd /d "%~dp0"
if not exist ".env" (
  echo No .env found. Run start.bat first.
  exit /b 1
)
set "DD_API_KEY=unused-for-stop"
docker compose --env-file .env -f compose.yaml down
exit /b %errorlevel%
