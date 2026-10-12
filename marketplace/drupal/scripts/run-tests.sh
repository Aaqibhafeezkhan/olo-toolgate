#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The toolgate_marketplace functional tests against a real helper and real packages (no mocks).
#
#   drupal/scripts/run-tests.sh [phpunit arguments]
#
# DRUPAL_ROOT is a Composer-installed Drupal project with the dev requirements (default: marketplace/drupal after
# `composer install`). The script starts the helper with a throwaway key of its own (never the Marketplace key) and
# PHP's built-in web server, runs PHPUnit on SQLite, and stops both.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=../../scripts/toolgate-dir.sh
source "$here/../../scripts/toolgate-dir.sh"
root="$(cd "${DRUPAL_ROOT:-$MARKETPLACE_DIR/drupal}" && pwd -P)"
[ -x "$root/vendor/bin/phpunit" ] || { echo "run-tests: $root/vendor/bin/phpunit is missing; run composer install" >&2; exit 1; }
# A separate DRUPAL_ROOT gets this checkout's module and theme.
for path in modules/custom/toolgate_marketplace themes/custom/olo_orgs; do
  if [ ! -e "$root/web/$path" ]; then
    mkdir -p "$(dirname "$root/web/$path")"
    ln -s "$MARKETPLACE_DIR/drupal/web/$path" "$root/web/$path"
  fi
done

if [ -z "${TOOLGATE_CLI:-}" ]; then
  (cd "$TOOLGATE_DIR" && cargo build -q --release -p toolgate-cli --bin toolgate)
  TOOLGATE_CLI="${CARGO_TARGET_DIR:-$TOOLGATE_DIR/target}/release/toolgate"
fi
jar="$MARKETPLACE_DIR/helper/build/libs/marketplace-helper.jar"
(cd "$MARKETPLACE_DIR/helper" && ./gradlew --no-daemon -q --console=plain jar)

work="$(mktemp -d)"
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
helper_port="$(free_port)"
web_port="$(free_port)"
token="test-$(openssl rand -hex 16)"
openssl genpkey -algorithm ed25519 -out "$work/test-key.pem" 2>/dev/null
mkdir -p "$root/web/sites/simpletest" "$work/private"

MARKETPLACE_ID=olo-labs-marketplace MARKETPLACE_HELPER_TOKEN="$token" \
  MARKETPLACE_SIGNING_KEY_FILE="$work/test-key.pem" MARKETPLACE_SIGNING_KEY_ID=olo-marketplace-test-1 \
  MARKETPLACE_HELPER_FILE_ROOT="$root/web/sites/simpletest" MARKETPLACE_HELPER_PORT="$helper_port" \
  TOOLGATE_CLI="$TOOLGATE_CLI" TOOLGATE_REVISION="$TOOLGATE_REVISION" \
  java -jar "$jar" >"$work/helper.log" 2>&1 &
pids+=("$!")

# The parent site's settings.php is loaded by the test runner; it only needs to resolve.
export MARKETPLACE_DB_DRIVER=sqlite MARKETPLACE_DB_NAME="$work/parent.sqlite" DRUPAL_HASH_SALT="test-$(openssl rand -hex 16)"
export MARKETPLACE_ID=olo-labs-marketplace MARKETPLACE_HELPER_TOKEN="$token" MARKETPLACE_PRIVATE_PATH="$work/private"
export MARKETPLACE_BASE_URL="http://127.0.0.1:$web_port" MARKETPLACE_CONFIG_READONLY=false
(cd "$root/web" && php -S "127.0.0.1:$web_port" .ht.router.php >"$work/web.log" 2>&1) &
pids+=("$!")

for attempt in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$helper_port/health" >/dev/null 2>&1 && break
  [ "$attempt" = 60 ] && { echo "run-tests: helper did not start" >&2; cat "$work/helper.log" >&2; exit 1; }
  sleep 1
done

export SIMPLETEST_DB="sqlite://localhost/$work/test.sqlite" SIMPLETEST_BASE_URL="http://127.0.0.1:$web_port"
export BROWSERTEST_OUTPUT_DIRECTORY="$work/browser"
export MARKETPLACE_TEST_TOOLGATE_CLI="$TOOLGATE_CLI" MARKETPLACE_TEST_HELPER_URL="http://127.0.0.1:$helper_port"
export MARKETPLACE_TEST_HELPER_TOKEN="$token" MARKETPLACE_TEST_FILE_ROOT="$root/web/sites/simpletest" MARKETPLACE_DIR
mkdir -p "$BROWSERTEST_OUTPUT_DIRECTORY"
cd "$root"
status=0
vendor/bin/phpunit -c web/core/phpunit.xml.dist "$@" web/modules/custom/toolgate_marketplace/tests || status=$?
if [ "$status" != 0 ]; then
  echo "--- helper log" >&2; tail -n 50 "$work/helper.log" >&2
fi
exit "$status"
