#!/usr/bin/env sh
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
set -eu

GRADLE_VERSION="9.7.1"
EXPECTED_SHA256="acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

GRADLE_HOME_BASE="${GRADLE_USER_HOME:-${HOME}/.gradle}/olo-toolgate-bootstrap"
INSTALL_DIR="${GRADLE_HOME_BASE}/gradle-${GRADLE_VERSION}"
ZIP_FILE="${GRADLE_HOME_BASE}/gradle-${GRADLE_VERSION}-bin.zip"

if [ -x "${INSTALL_DIR}/bin/gradle" ]; then
  exec "${INSTALL_DIR}/bin/gradle" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  CURRENT="$(gradle --version 2>/dev/null | awk '/^Gradle / { print $2; exit }')"
  if [ "${CURRENT:-}" = "${GRADLE_VERSION}" ]; then
    exec gradle "$@"
  fi
fi

mkdir -p "${GRADLE_HOME_BASE}"

download() {
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --output "${ZIP_FILE}" "${DIST_URL}"
  elif command -v wget >/dev/null 2>&1; then
    wget --https-only --tries=3 --output-document="${ZIP_FILE}" "${DIST_URL}"
  else
    echo "ERROR: curl or wget is required to bootstrap Gradle ${GRADLE_VERSION}." >&2
    exit 1
  fi
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "ERROR: sha256sum or shasum is required to verify Gradle." >&2
    exit 1
  fi
}

if [ ! -f "${ZIP_FILE}" ]; then
  echo "Bootstrapping Gradle ${GRADLE_VERSION}..."
  download
fi

ACTUAL_SHA256="$(sha256 "${ZIP_FILE}")"
if [ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]; then
  rm -f "${ZIP_FILE}"
  echo "ERROR: Gradle distribution checksum mismatch." >&2
  echo "Expected: ${EXPECTED_SHA256}" >&2
  echo "Actual:   ${ACTUAL_SHA256}" >&2
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "ERROR: unzip is required to bootstrap Gradle." >&2
  exit 1
fi

TMP_DIR="${GRADLE_HOME_BASE}/.extract-${GRADLE_VERSION}-$$"
rm -rf "${TMP_DIR}"
mkdir -p "${TMP_DIR}"
trap 'rm -rf "${TMP_DIR}"' EXIT INT TERM

unzip -q "${ZIP_FILE}" -d "${TMP_DIR}"
rm -rf "${INSTALL_DIR}"
mv "${TMP_DIR}/gradle-${GRADLE_VERSION}" "${INSTALL_DIR}"

exec "${INSTALL_DIR}/bin/gradle" "$@"
