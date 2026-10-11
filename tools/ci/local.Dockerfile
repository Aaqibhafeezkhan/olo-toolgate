# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Job actions install Java/Node/Python from the versions in workflow YAML.
FROM ghcr.io/catthehacker/ubuntu:act-24.04
ARG RUST_VERSION
RUN test -n "$RUST_VERSION" \
    && apt-get update \
    && apt-get install -y --no-install-recommends build-essential pkg-config libssl-dev php-cli make docker.io ca-certificates curl \
    && curl --fail --location --proto '=https' --tlsv1.2 https://sh.rustup.rs -o /tmp/rustup-init.sh \
    && sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain "$RUST_VERSION" --component rustfmt,clippy \
    && rm /tmp/rustup-init.sh \
    && rm -rf /var/lib/apt/lists/*
ENV PATH="/root/.cargo/bin:${PATH}"
