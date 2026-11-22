#!/bin/sh
# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
# Fresh PostgreSQL initialization only. The official entrypoint does not replay
# this script on existing stores. No credential values appear in process argv.
set -eu
export TOOLGATE_MIGRATOR_PASSWORD="$(cat /run/database/migrator-password)"
export TOOLGATE_RUNTIME_PASSWORD="$(cat /run/database/runtime-password)"
export TOOLGATE_PLATFORM_PASSWORD="$(cat /run/database/platform-password)"
test -n "$TOOLGATE_MIGRATOR_PASSWORD"
test -n "$TOOLGATE_RUNTIME_PASSWORD"
test -n "$TOOLGATE_PLATFORM_PASSWORD"
psql --username postgres --dbname postgres --set ON_ERROR_STOP=1 <<'SQL'
\getenv migrator_password TOOLGATE_MIGRATOR_PASSWORD
\getenv runtime_password TOOLGATE_RUNTIME_PASSWORD
\getenv platform_password TOOLGATE_PLATFORM_PASSWORD
CREATE ROLE toolgate_control_runtime NOLOGIN;
CREATE ROLE toolgate_control_platform NOLOGIN;
CREATE ROLE control_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE control_app LOGIN IN ROLE toolgate_control_runtime PASSWORD :'runtime_password';
CREATE ROLE control_provisioner LOGIN IN ROLE toolgate_control_platform PASSWORD :'platform_password';
CREATE DATABASE toolgate OWNER control_migrator;
REVOKE CONNECT ON DATABASE toolgate FROM PUBLIC;
GRANT CONNECT ON DATABASE toolgate TO control_migrator, control_app, control_provisioner;
SQL
unset TOOLGATE_MIGRATOR_PASSWORD TOOLGATE_RUNTIME_PASSWORD TOOLGATE_PLATFORM_PASSWORD
