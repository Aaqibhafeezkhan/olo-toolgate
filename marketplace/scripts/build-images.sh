#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Builds the local images for the three ways to run OLO Orgs (docs/deployment.md):
#   olo-toolgate-marketplace-helper:local   the helper (every setup)
#   deploy/.seed                            the 18 example packages the Drupal image bakes in
#   olo-orgs-marketplace-drupal:local       the site image (full stack and local stack)
#   olo-orgs-standalone:local               the site with SQLite, files and secrets in one container (--standalone)
#
#   scripts/build-images.sh                       # full site image: needs drupal/composer.lock and drupal.org
#   scripts/build-images.sh --core-only           # no contrib modules: core, drush and the marketplace module only
#   scripts/build-images.sh --core-only --standalone
#
# --seed-on-host builds the examples with this machine's toolgate CLI and JDK instead of the seed stage.
# --ca FILE (or MARKETPLACE_BUILD_CA) passes a CA bundle to builds behind a TLS-intercepting proxy; HTTPS_PROXY, when
# set, is passed to the builds, which then use the host network.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=toolgate-dir.sh
source "$here/toolgate-dir.sh"

core_only=false standalone=false seed_on_host=false ca="${MARKETPLACE_BUILD_CA:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --core-only) core_only=true ;;
    --standalone) standalone=true ;;
    --seed-on-host) seed_on_host=true ;;
    --ca) ca="$2"; shift ;;
    *) echo "build-images: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

build=(docker buildx build --build-arg TOOLGATE_REVISION="$TOOLGATE_REVISION")
[ -n "$ca" ] && build+=(--secret "id=ca,src=$ca")
if [ -n "${HTTPS_PROXY:-${https_proxy:-}}" ]; then
  proxy="${HTTPS_PROXY:-$https_proxy}"
  build+=(--network host --build-arg "HTTPS_PROXY=$proxy" --build-arg "https_proxy=$proxy"
    --build-arg "NO_PROXY=${NO_PROXY:-${no_proxy:-}}" --build-arg "no_proxy=${NO_PROXY:-${no_proxy:-}}")
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "build-images: helper"
"${build[@]}" --load -f "$MARKETPLACE_DIR/helper/Dockerfile" --build-context marketplace="$MARKETPLACE_DIR" \
  -t olo-toolgate-marketplace-helper:local "$TOOLGATE_DIR"

seed="$MARKETPLACE_DIR/deploy/.seed"
echo "build-images: example packages into $seed"
rm -rf "$seed"
if [ "$seed_on_host" = true ]; then
  bash "$MARKETPLACE_DIR/seed/build-seed.sh" "$seed/seed"
else
  "${build[@]}" -f "$MARKETPLACE_DIR/seed/Dockerfile.stage" --target seed \
    --build-context marketplace="$MARKETPLACE_DIR" --output "type=local,dest=$seed" "$TOOLGATE_DIR"
fi

context="$MARKETPLACE_DIR/drupal"
args=()
if [ "$core_only" = true ]; then
  # The same image without contrib modules, for machines that can't reach drupal.org: composer.json without them and
  # its own lock file, modules.yml with no contrib entries (so build-modules.php --check still holds), no redis
  # extension. settings.php then keeps files on local paths and the cache in the database.
  echo "build-images: core-only Drupal build context"
  context="$work/drupal"
  mkdir -p "$context"
  (cd "$MARKETPLACE_DIR/drupal" && tar --exclude=./vendor --exclude=./web/core --exclude=./web/modules/contrib -cf - .) | tar -xf - -C "$context"
  php -r '
    $file = $argv[1];
    $c = json_decode(file_get_contents($file), TRUE);
    foreach (array_keys($c["require"]) as $name) {
      if (str_starts_with($name, "drupal/") && !str_starts_with($name, "drupal/core")) unset($c["require"][$name]);
    }
    $c["repositories"] = [];
    file_put_contents($file, json_encode($c, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n");
  ' "$context/composer.json"
  sed -i '/^modules:$/,/^# Core modules/{/^modules:$/!{/^# Core modules/!d}}; s/^modules:$/modules: []\n/' "$context/modules.yml"
  if command -v composer >/dev/null; then
    (cd "$context" && env COMPOSER_ALLOW_SUPERUSER=1 ${ca:+SSL_CERT_FILE="$ca"} composer update --no-install --no-interaction --no-progress --quiet)
  else
    docker run --rm -v "$context:/app" -w /app ${ca:+-v "$ca:/ca.pem:ro" -e SSL_CERT_FILE=/ca.pem} \
      composer:2.10.3 update --no-install --no-interaction --no-progress --quiet
  fi
  args+=(--build-arg REDIS_EXTENSION=false)
elif [ ! -f "$context/composer.lock" ]; then
  echo "build-images: drupal/composer.lock is missing; run composer update in drupal/ (needs drupal.org) or use --core-only" >&2
  exit 1
fi

echo "build-images: Drupal site image"
"${build[@]}" --load -f "$context/Dockerfile" "${args[@]}" \
  --build-context marketplace="$MARKETPLACE_DIR" --build-context seed="$seed" \
  -t olo-orgs-marketplace-drupal:local "$context"

if [ "$standalone" = true ]; then
  echo "build-images: standalone image"
  "${build[@]}" --load -f "$MARKETPLACE_DIR/standalone/Dockerfile" -t olo-orgs-standalone:local "$MARKETPLACE_DIR/standalone"
fi
echo "build-images: done"
