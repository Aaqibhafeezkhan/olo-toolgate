#!/usr/bin/env sh
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
set -eu

required="
README.md
CONTRIBUTING.md
ARCHITECTURE.md
SECURITY.md
docs/README.md
docs/planning/README.md
docs/enterprise-access-control/README.md
docs/architecture/overview.md
docs/control-plane/device-registry.md
docs/security/security-invariants.md
"

for f in $required; do
  if [ ! -f "$f" ]; then
    printf 'Missing required documentation: %s\n' "$f" >&2
    exit 1
  fi
done

printf '%s\n' 'Documentation structure check passed.'
