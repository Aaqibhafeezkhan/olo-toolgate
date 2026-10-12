#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# One-time generation of the Marketplace Ed25519 signing key (design §6.1). Refuses to run when a key already exists,
# so a restart or redeploy can never replace it. Prints the public key and its fingerprint for the keys file, the
# "About signing" page and each ToolGate's TOOLGATE_CONTROL_MARKETPLACE_KEYS.
#
#   scripts/init-signing-key.sh [key-file]     default: deploy/secrets/marketplace_signing_key
#
# Back the key up with your operator procedure. It belongs only in the helper's secret mount: never in Git, Drupal's
# volumes, the database or its backups.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
key="${1:-$here/../deploy/secrets/marketplace_signing_key}"
key_id="${MARKETPLACE_SIGNING_KEY_ID:-olo-marketplace-$(date -u +%Y)-1}"

command -v openssl >/dev/null || { echo "init-signing-key: openssl is required" >&2; exit 1; }
if [ -e "$key" ]; then
  echo "init-signing-key: $key already exists; refusing to replace the signing key" >&2
  exit 1
fi
mkdir -p "$(dirname "$key")"
chmod 700 "$(dirname "$key")"
umask 077
tmp="$(mktemp "$(dirname "$key")/.key.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
openssl genpkey -algorithm ed25519 -out "$tmp"
# Refuse again at the last moment in case two runs raced; ln fails if the target exists.
ln "$tmp" "$key" || { echo "init-signing-key: $key appeared while generating; refusing to replace it" >&2; exit 1; }
chmod 400 "$key"

pub="$(mktemp)"
trap 'rm -f "$tmp" "$pub"' EXIT
# The raw 32-byte public key is the tail of its DER SubjectPublicKeyInfo; kept in a file because it can hold NUL bytes.
openssl pkey -in "$key" -pubout -outform DER | tail -c 32 >"$pub"
public="$(base64 <"$pub" | tr -d '\n')"
fingerprint="sha256:$(openssl dgst -sha256 -hex <"$pub" | awk '{print $NF}')"
echo "key file:    $key"
echo "keyId:       $key_id"
echo "publicKey:   $public"
echo "fingerprint: $fingerprint"
echo "ToolGate:    TOOLGATE_CONTROL_MARKETPLACE_KEYS=$key_id:$public"
