#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The deploy job (prompt §6.2): run once per release, never by web replicas.
#   empty database -> drush site:install --existing-config
#   otherwise      -> drush deploy (updates, config import, deploy hooks)
# then, with MARKETPLACE_SEED_EXAMPLES=true (the default), the example seed.
set -euo pipefail
cd /opt/drupal
export MARKETPLACE_CONFIG_READONLY=false

for attempt in $(seq 1 60); do
  if state="$(php scripts/db-state.php 2>/dev/null)"; then
    break
  fi
  [ "$attempt" = 60 ] && { echo "deploy: database unreachable" >&2; exit 1; }
  sleep 2
done

if [ "$state" = installed ]; then
  echo "deploy: existing site, running drush deploy"
  drush deploy -y
else
  echo "deploy: empty database, installing from config/sync"
  password_file="${MARKETPLACE_ADMIN_PASSWORD_FILE:-/run/secrets/marketplace_admin_password}"
  if [ ! -s "$password_file" ]; then
    echo "deploy: $password_file is required for the first install" >&2
    exit 1
  fi
  drush site:install --existing-config -y \
    --account-name="${MARKETPLACE_ADMIN_USER:-admin}" \
    --account-mail="${MARKETPLACE_ADMIN_MAIL:-${MARKETPLACE_MAIL_FROM:-admin@example.com}}" \
    --account-pass="$(cat "$password_file")"
fi

if [ "${MARKETPLACE_SEED_EXAMPLES:-true}" = "true" ]; then
  # The seed signs through the helper, which may still be starting on a first install.
  for attempt in $(seq 1 60); do
    if php -r 'exit(@file_get_contents(rtrim(getenv("MARKETPLACE_HELPER_URL") ?: "http://helper:8080", "/") . "/health") === false ? 1 : 0);'; then
      break
    fi
    [ "$attempt" = 60 ] && echo "deploy: helper unreachable; the seed will report what is missing" >&2
    sleep 2
  done
  drush toolgate-marketplace:seed /opt/olo/seed
fi
drush toolgate-marketplace:seed-status
