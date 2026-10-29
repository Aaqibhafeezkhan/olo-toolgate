@echo off
setlocal
cd /d "%~dp0"
if not exist ".env" (
  copy /y ".env.example" ".env" >nul
  echo Created .env from .env.example.
)
docker compose --env-file .env -f compose.yaml up -d
if errorlevel 1 (
  echo Startup failed. Check Docker Desktop, .env settings, and the ToolGate network.
  exit /b 1
)
echo Grafana: http://localhost:3000  Login: admin / admin unless configured otherwise
echo Prometheus: http://localhost:9090
docker compose --env-file .env -f compose.yaml exec -T quickstart /opt/quickstart-python/bin/python /opt/monitoring/use-cases.py --bootstrap --repeat 3
exit /b %errorlevel%
