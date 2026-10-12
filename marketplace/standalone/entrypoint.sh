#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Installs or updates the site (deploy.sh, which also seeds the examples through the helper), runs
# cron every five minutes, then serves the site on 8080. Drupal's own secrets (hash salt, helper
# token, first admin password) are generated once into /var/lib/olo-orgs/secrets. The signing key is not: it belongs
# to the helper alone (compose.yaml, profile init-key).
set -euo pipefail
data=/var/lib/olo-orgs

install -d -o www-data -g www-data -m 0755 "$data" "$data/public" "$data/private" "$data/db"
install -d -o root -g www-data -m 0750 "$data/secrets"
secret() {
  local file="$data/secrets/$1"
  if [ ! -s "$file" ]; then
    (umask 027; openssl rand "$2" "$3" | tr -d '/+=\n' >"$file")
    chgrp www-data "$file"
  fi
}
secret hash_salt -hex 32
secret helper_token -hex 32
secret admin_password -base64 15
# The helper reads the token from the shared volume.
chmod 0644 "$data/secrets/helper_token"
chmod 0755 "$data/secrets"

stop() {
  apachectl -k graceful-stop 2>/dev/null || true
  exit 0
}
trap stop TERM INT

runuser -u www-data -- env MARKETPLACE_CONFIG_READONLY=false /opt/drupal/scripts/deploy.sh
echo "olo-orgs: site ready at ${MARKETPLACE_BASE_URL}; sign in as ${MARKETPLACE_ADMIN_USER:-admin}," \
  "password in $data/secrets/admin_password (docker compose exec drupal cat $data/secrets/admin_password)"

(while true; do sleep 300; runuser -u www-data -- /opt/drupal/scripts/cron.sh >/dev/null 2>&1 || true; done) &
mkdir -p /tmp/apache2/lock /tmp/apache2/log /tmp/opcache
chown -R www-data:www-data /tmp/apache2 /tmp/opcache
apache2-foreground &
wait $!
