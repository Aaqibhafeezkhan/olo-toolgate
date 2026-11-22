#!/bin/sh
# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
# Host bind-mount permissions (especially Docker Desktop) are not server key
# permissions. Publish only TLS files into private container-local tmpfs.
set -eu
mkdir -p /run/toolgate-postgres
cp /run/database/server.crt /run/toolgate-postgres/server.crt
cp /run/database/server.key /run/toolgate-postgres/server.key
chown -R postgres:postgres /run/toolgate-postgres
chmod 0700 /run/toolgate-postgres
chmod 0600 /run/toolgate-postgres/server.key
chmod 0644 /run/toolgate-postgres/server.crt
exec /usr/local/bin/docker-entrypoint.sh "$@"
