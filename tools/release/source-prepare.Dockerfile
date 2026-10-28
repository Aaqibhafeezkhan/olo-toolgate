# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
FROM python:3.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
COPY tools/requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir -r /tmp/requirements.txt
