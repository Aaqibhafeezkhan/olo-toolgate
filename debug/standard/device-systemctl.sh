#!/bin/sh
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Docker supervises the foreground service; accept only the installer's fixed operations.
case "$*" in
    'daemon-reload'|'enable --now olo-toolgate-client.service') exit 0 ;;
    *) echo 'Docker device: unsupported systemctl operation' >&2; exit 1 ;;
esac
