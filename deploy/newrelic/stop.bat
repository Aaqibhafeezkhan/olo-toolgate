@echo off
setlocal
cd /d "%~dp0"
if not exist ".env" (
  echo No .env found. Run start.bat first.
  exit /b 1
)
set "NEW_RELIC_LICENSE_KEY=unused-for-stop"
docker compose --env-file .env -f compose.yaml down
exit /b %errorlevel%
