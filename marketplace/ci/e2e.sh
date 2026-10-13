#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# End to end against the PR 2 stack (implementation prompt §PR 3): a throwaway OLO Orgs stack on HTTPS, seeded with
# every example package, and a Quickstart ToolGate that trusts it. ci/e2e.py then imports examples.notify and
# examples.health through Control, deploys, enables and grants them, and calls them through /mcp.
#
# Needs the images, built beforehand:
#   scripts/build-images.sh --core-only                          olo-orgs-marketplace-drupal:local, helper:local
#   python3 "$TOOLGATE_DIR/tools/quickstart/check.py" --build --build-only    the Quickstart image
# QUICKSTART_IMAGE overrides the Quickstart image tag. Everything this script creates (containers, volumes, network,
# the test signing key) is removed on exit; it never touches deploy/.env or deploy/secrets.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=../scripts/toolgate-dir.sh
source "$here/../scripts/toolgate-dir.sh"

QUICKSTART_IMAGE="${QUICKSTART_IMAGE:-olo-toolgate-quickstart:enterprise-validation}"
for image in olo-orgs-marketplace-drupal:local olo-toolgate-marketplace-helper:local "$QUICKSTART_IMAGE"; do
  docker image inspect "$image" >/dev/null 2>&1 || { echo "e2e: image $image is missing; see the header of ci/e2e.sh" >&2; exit 1; }
done

host=marketplace.internal   # Not a public name, so Caddy issues it from its own local CA.
subnet="${MARKETPLACE_E2E_SUBNET:-172.30.77.0/24}"
project="olo-orgs-e2e-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
work="$(mktemp -d)"
compose() {
  docker compose --project-name "$project" --project-directory "$work/deploy" --env-file "$work/deploy/.env" \
    -f "$MARKETPLACE_DIR/deploy/compose.yaml" -f "$MARKETPLACE_DIR/deploy/compose.local.yaml" -f "$work/e2e.yaml" "$@"
}
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then compose logs --no-color --tail 200 deploy helper caddy >&2 || true; fi
  compose --profile init-key down -v --remove-orphans >/dev/null 2>&1 || true
  # The key file belongs to the helper's user; remove it from a container rather than needing root here.
  docker run --rm --network none -v "$work/secrets:/secrets" --entrypoint sh olo-orgs-marketplace-drupal:local \
    -c 'rm -f /secrets/*' >/dev/null 2>&1 || true
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

mkdir -p "$work/deploy" "$work/secrets"
for name in helper_token drupal_hash_salt admin_password; do
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$work/secrets/$name"
done
chmod 0444 "$work/secrets/helper_token" "$work/secrets/drupal_hash_salt" "$work/secrets/admin_password"
db_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
sed -e "s|^MARKETPLACE_BASE_URL=.*|MARKETPLACE_BASE_URL=https://$host|" \
    -e "s|^MARKETPLACE_ID=.*|MARKETPLACE_ID=olo-orgs-e2e|" \
    -e "s|^MARKETPLACE_SITE_ADDRESS=.*|MARKETPLACE_SITE_ADDRESS=$host|" \
    -e "s|^MARKETPLACE_PUBLIC_HOST=.*|MARKETPLACE_PUBLIC_HOST=$host|" \
    -e "s|^MARKETPLACE_SEED_STRICT=.*|MARKETPLACE_SEED_STRICT=true|" \
    -e "s|^MARKETPLACE_DB_PASSWORD=.*|MARKETPLACE_DB_PASSWORD=$db_password|" \
    -e "s|^MARKETPLACE_S3_ENDPOINT=.*|MARKETPLACE_S3_ENDPOINT=|" \
    -e "s|^MARKETPLACE_S3_ACCESS_KEY=.*|MARKETPLACE_S3_ACCESS_KEY=unused|" \
    -e "s|^MARKETPLACE_S3_SECRET_KEY=.*|MARKETPLACE_S3_SECRET_KEY=unused|" \
    -e "s|^MARKETPLACE_SIGNING_KEY_ID=.*|MARKETPLACE_SIGNING_KEY_ID=olo-orgs-e2e-1|" \
    "$MARKETPLACE_DIR/deploy/.env.example" > "$work/deploy/.env"
cat > "$work/e2e.yaml" <<EOF
services:
  caddy:
    ports: !reset []
    networks:
      web:
        aliases: [$host]
  drupal:
    deploy: { replicas: 1 }
    environment: { MARKETPLACE_REVERSE_PROXY_ADDRESSES: "$subnet" }
  deploy:
    environment: { MARKETPLACE_REVERSE_PROXY_ADDRESSES: "$subnet" }
  cron:
    environment: { MARKETPLACE_REVERSE_PROXY_ADDRESSES: "$subnet" }
  helper:
    deploy: { replicas: 1 }
  init-key:
    volumes: !override
      - $MARKETPLACE_DIR/scripts:/marketplace/scripts:ro
      - $work/secrets:/secrets
secrets:
  signing_key: { file: $work/secrets/marketplace_signing_key }
  helper_token: { file: $work/secrets/helper_token }
  hash_salt: { file: $work/secrets/drupal_hash_salt }
  admin_password: { file: $work/secrets/admin_password }
networks:
  web:
    ipam: !override
      config:
        - subnet: $subnet
EOF

echo "e2e: creating a test signing key with the explicit init-key step"
compose --profile init-key run --rm init-key
echo "e2e: starting $project"
compose up -d --wait --wait-timeout 600

# The public key from the pinned key file Control will trust, and the CA Caddy signed the site with.
public_key="$(docker run --rm --network none -v "$work/secrets:/secrets:ro" --user 0:0 --entrypoint sh \
  olo-orgs-marketplace-drupal:local -c 'openssl pkey -in /secrets/marketplace_signing_key -pubout -outform DER | tail -c 32 | base64')"
caddy="$(compose ps -q caddy)"
for _ in $(seq 1 60); do
  docker cp "$caddy:/data/caddy/pki/authorities/local/root.crt" "$work/ca.pem" 2>/dev/null && break
  # Caddy creates its local CA when it first needs a certificate for the site.
  docker run --rm --network "${project}_web" --entrypoint sh olo-orgs-marketplace-drupal:local \
    -c "openssl s_client -connect $host:443 -servername $host </dev/null" >/dev/null 2>&1 || true
  sleep 2
done
[ -s "$work/ca.pem" ] || { echo "e2e: Caddy issued no local CA" >&2; exit 1; }
[ "$(printf %s "$public_key" | base64 -d 2>/dev/null | wc -c)" -eq 32 ] || { echo "e2e: could not read the test public key" >&2; exit 1; }
chmod 0444 "$work/ca.pem"

python3 "$here/e2e.py" --image "$QUICKSTART_IMAGE" --network "${project}_web" --ca "$work/ca.pem" \
  --base-url "https://$host" --marketplace-id olo-orgs-e2e --keys "olo-orgs-e2e-1:$public_key"
echo "e2e: passed"
