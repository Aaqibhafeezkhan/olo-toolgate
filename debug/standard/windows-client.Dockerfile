# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
FROM rust:1.94.1-bookworm@sha256:6ae102bdbf528294bc79ad6e1fae682f6f7c2a6e6621506ba959f9685b308a55
RUN apt-get update && apt-get install -y --no-install-recommends gcc-mingw-w64-x86-64 python3 \
    && rm -rf /var/lib/apt/lists/* \
    && rustup component add clippy rustfmt \
    && rustup target add x86_64-pc-windows-gnu
WORKDIR /work
