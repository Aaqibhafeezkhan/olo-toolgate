#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Builds and pushes the Marketplace images (implementation prompt §7). Called by independent publish-marketplace jobs
# in .github/workflows/development-release.yml (main/tags) and manual-development-release.yml (manual).
# Marketplace and ToolGate publication run in parallel; a failure in either job does not block the other.
#
# Registries: Docker Hub (DOCKERHUB_NAMESPACE or DOCKERHUB_USERNAME, with DOCKERHUB_TOKEN), as the existing release
# scripts use, and GitHub Packages (ghcr.io/<owner>) when GH_TOKEN and GITHUB_REPOSITORY_OWNER are set.
# Tags: dev-<sha12>, <VERSION>-<sha12> and dev. Writes <name>_image and <name>_digest to $GITHUB_OUTPUT.
#
# The Drupal image copies the example packages built by the seed stage, so that stage is built once first.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=../scripts/toolgate-dir.sh
source "$here/../scripts/toolgate-dir.sh"

# name|image|Dockerfile (relative to MARKETPLACE_DIR)|build context
IMAGES=(
  "helper|olo-toolgate-marketplace-helper|helper/Dockerfile|$TOOLGATE_DIR"
  "drupal|olo-orgs-marketplace-drupal|drupal/Dockerfile|$MARKETPLACE_DIR/drupal"
)

sha="${RELEASE_SHA:-$TOOLGATE_REVISION}"
short="${sha:0:12}"
version="$(tr -d '[:space:]' <"$TOOLGATE_DIR/VERSION")"
namespace="${DOCKERHUB_NAMESPACE:-${DOCKERHUB_USERNAME:-}}"
if [ -z "$namespace" ] || [ -z "${DOCKERHUB_TOKEN:-}" ]; then
  echo "publish-images: DOCKERHUB_NAMESPACE or DOCKERHUB_USERNAME, and DOCKERHUB_TOKEN, are required" >&2
  exit 1
fi
printf '%s' "$DOCKERHUB_TOKEN" | docker login --username "${DOCKERHUB_USERNAME:-$namespace}" --password-stdin
ghcr=""
if [ -n "${GH_TOKEN:-}" ] && [ -n "${GITHUB_REPOSITORY_OWNER:-}" ]; then
  ghcr="ghcr.io/$(printf '%s' "$GITHUB_REPOSITORY_OWNER" | tr '[:upper:]' '[:lower:]')"
  printf '%s' "$GH_TOKEN" | docker login ghcr.io --username "${GITHUB_ACTOR:-$GITHUB_REPOSITORY_OWNER}" --password-stdin
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
docker buildx build --file "$MARKETPLACE_DIR/seed/Dockerfile.stage" --target seed \
  --build-context marketplace="$MARKETPLACE_DIR" \
  --build-arg TOOLGATE_REVISION="$sha" \
  --output type=local,dest="$work/seed-out" "$TOOLGATE_DIR"

for entry in "${IMAGES[@]}"; do
  IFS='|' read -r name image dockerfile context <<<"$entry"
  if [ "$name" = drupal ] && [ ! -f "$MARKETPLACE_DIR/drupal/composer.lock" ]; then
    # The site image is built only from a committed lock file (prompt §6.3); until it exists, publish the rest.
    echo "::warning::publish-images: marketplace/drupal/composer.lock is not committed; skipping the Drupal image"
    continue
  fi
  tags=()
  for repository in "docker.io/$namespace/$image" ${ghcr:+"$ghcr/$image"}; do
    for tag in "dev-$short" "$version-$short" dev; do
      tags+=(--tag "$repository:$tag")
    done
  done
  docker buildx build --push --provenance=false \
    --file "$MARKETPLACE_DIR/$dockerfile" \
    --build-context marketplace="$MARKETPLACE_DIR" \
    --build-context seed="$work/seed-out" \
    --build-arg TOOLGATE_REVISION="$sha" \
    --label "io.ololabs.toolgate.revision=$sha" \
    --metadata-file "$work/$name.json" \
    "${tags[@]}" "$context"
  digest="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["containerimage.digest"])' "$work/$name.json")"
  echo "publish-images: docker.io/$namespace/$image@$digest (dev-$short, $version-$short, dev)${ghcr:+ and $ghcr/$image}"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "${name}_image=docker.io/$namespace/$image" >>"$GITHUB_OUTPUT"
    echo "${name}_digest=$digest" >>"$GITHUB_OUTPUT"
  fi
done
