#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# Scheduled cron (Compose loop or Kubernetes CronJob); automated_cron stays off.
set -euo pipefail
cd /opt/drupal
exec drush cron
