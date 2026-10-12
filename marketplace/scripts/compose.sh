#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The only way to run deploy/compose.yaml. Resolves TOOLGATE_SRC, builds the example packages with the seed stage
# (seed/Dockerfile.stage, target "seed") into deploy/.seed, which the Drupal image copies, then runs docker compose
# with every argument passed through:
#
#   scripts/compose.sh --profile init-key run --rm init-key    # once per Marketplace
#   scripts/compose.sh up -d --build
#   scripts/compose.sh up -d --scale drupal=3
#   scripts/compose.sh --local up -d     # the local stack (deploy/compose.local.yaml) on images from
#                                        # scripts/build-images.sh --core-only; nothing is built here
#
# MARKETPLACE_SKIP_SEED_BUILD=true reuses deploy/.seed as it is.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=toolgate-dir.sh
source "$here/toolgate-dir.sh"

export MARKETPLACE_SEED_OUT="$MARKETPLACE_DIR/deploy/.seed"
files=(-f "$MARKETPLACE_DIR/deploy/compose.yaml")
if [ "${1:-}" = --local ]; then
  shift
  files+=(-f "$MARKETPLACE_DIR/deploy/compose.local.yaml")
  export MARKETPLACE_SKIP_SEED_BUILD=true
fi
[ -f "$MARKETPLACE_DIR/deploy/.env" ] || { echo "compose: copy deploy/.env.example to deploy/.env first" >&2; exit 1; }

needs_build=false
for arg in "$@"; do
  case "$arg" in up|build|create|run) needs_build=true ;; esac
done
if [ "$needs_build" = true ] && [ "${MARKETPLACE_SKIP_SEED_BUILD:-false}" != true ]; then
  echo "compose: building the example packages into $MARKETPLACE_SEED_OUT"
  rm -rf "$MARKETPLACE_SEED_OUT"
  docker buildx build \
    --file "$MARKETPLACE_DIR/seed/Dockerfile.stage" \
    --target seed \
    --build-context marketplace="$MARKETPLACE_DIR" \
    --build-arg TOOLGATE_REVISION="$TOOLGATE_REVISION" \
    --output type=local,dest="$MARKETPLACE_SEED_OUT" \
    "$TOOLGATE_DIR"
fi
mkdir -p "$MARKETPLACE_SEED_OUT/seed"

exec docker compose --project-directory "$MARKETPLACE_DIR/deploy" "${files[@]}" "$@"
