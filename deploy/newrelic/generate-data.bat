@echo off
setlocal
cd /d "%~dp0"
docker compose --env-file .env -f compose.yaml exec -T quickstart /opt/quickstart-python/bin/python /opt/monitoring/use-cases.py %*
exit /b %errorlevel%
