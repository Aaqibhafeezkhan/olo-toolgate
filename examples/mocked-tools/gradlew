#!/usr/bin/env sh
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
set -eu

# The distribution, its SHA-256 and the fallback source are read from
# gradle/wrapper/gradle-wrapper.properties, shared with gradle/bootstrap.ps1.
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROPERTIES="${ROOT_DIR}/gradle/wrapper/gradle-wrapper.properties"

property() {
  sed -n "s/^$1=//p" "${PROPERTIES}" | head -n 1 | sed 's/\\//g' | tr -d '\r'
}

DIST_URL="$(property distributionUrl)"
FALLBACK_URL="$(property distributionFallbackUrl)"
EXPECTED_SHA256="$(property distributionSha256Sum)"
GRADLE_VERSION="$(printf '%s\n' "${DIST_URL}" | sed -n 's/.*gradle-\([0-9][0-9.]*\)-bin\.zip$/\1/p')"
if [ -z "${DIST_URL}" ] || [ -z "${EXPECTED_SHA256}" ] || [ -z "${GRADLE_VERSION}" ]; then
  echo "ERROR: ${PROPERTIES} must define distributionUrl and distributionSha256Sum." >&2
  exit 1
fi

GRADLE_HOME_BASE="${GRADLE_USER_HOME:-${HOME}/.gradle}/olo-toolgate-bootstrap"
INSTALL_DIR="${GRADLE_HOME_BASE}/gradle-${GRADLE_VERSION}"
ZIP_FILE="${GRADLE_HOME_BASE}/gradle-${GRADLE_VERSION}-bin.zip"
PART_FILE="${ZIP_FILE}.part-$$"

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

# One source with bounded retries. GitHub API asset URLs need an explicit
# octet-stream Accept header; the redirect then lands on GitHub's asset CDN.
fetch() {
  accept="*/*"
  case "$1" in https://api.github.com/*) accept="application/octet-stream" ;; esac
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --proto '=https' --proto-redir '=https' \
      --connect-timeout 20 --max-time 900 \
      --retry 4 --retry-delay 5 --retry-max-time 300 \
      --header "Accept: ${accept}" --output "${PART_FILE}" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget --https-only --tries=4 --waitretry=5 --timeout=60 \
      --retry-on-http-error=429,500,502,503,504 \
      --header="Accept: ${accept}" --output-document="${PART_FILE}" "$1"
  else
    echo "ERROR: curl or wget is required to bootstrap Gradle ${GRADLE_VERSION}." >&2
    exit 1
  fi
}

trap 'rm -f "${PART_FILE}"' EXIT INT TERM

if [ -f "${ZIP_FILE}" ] && [ "$(sha256 "${ZIP_FILE}")" != "${EXPECTED_SHA256}" ]; then
  rm -f "${ZIP_FILE}"
fi

if [ ! -f "${ZIP_FILE}" ]; then
  echo "Bootstrapping Gradle ${GRADLE_VERSION}..."
  # An explicit mirror (for example a corporate proxy) is tried first. Every
  # source must produce the exact pinned archive: a source adds availability,
  # never a different distribution.
  for SOURCE in ${TOOLGATE_GRADLE_DIST_URL:-} "${DIST_URL}" ${FALLBACK_URL}; do
    rm -f "${PART_FILE}"
    if ! fetch "${SOURCE}"; then
      echo "WARNING: Gradle download failed from ${SOURCE}; trying the next source." >&2
      continue
    fi
    ACTUAL_SHA256="$(sha256 "${PART_FILE}")"
    if [ "${ACTUAL_SHA256}" = "${EXPECTED_SHA256}" ]; then
      mv -f "${PART_FILE}" "${ZIP_FILE}"
      break
    fi
    echo "WARNING: Gradle checksum mismatch from ${SOURCE} (got ${ACTUAL_SHA256}); trying the next source." >&2
  done
  if [ ! -f "${ZIP_FILE}" ]; then
    echo "ERROR: Gradle ${GRADLE_VERSION} could not be downloaded with SHA-256 ${EXPECTED_SHA256} from any source." >&2
    exit 1
  fi
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "ERROR: unzip is required to bootstrap Gradle." >&2
  exit 1
fi

TMP_DIR="${GRADLE_HOME_BASE}/.extract-${GRADLE_VERSION}-$$"
rm -rf "${TMP_DIR}"
mkdir -p "${TMP_DIR}"
trap 'rm -rf "${TMP_DIR}"; rm -f "${PART_FILE}"' EXIT INT TERM

unzip -q "${ZIP_FILE}" -d "${TMP_DIR}"
rm -rf "${INSTALL_DIR}"
mv "${TMP_DIR}/gradle-${GRADLE_VERSION}" "${INSTALL_DIR}"
# exec replaces this shell, so the EXIT trap would never remove the extraction directory.
rm -rf "${TMP_DIR}"
trap - EXIT INT TERM

exec "${INSTALL_DIR}/bin/gradle" "$@"
