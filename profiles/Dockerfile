# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS cli
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY packages/tenant-identity/rust packages/tenant-identity/rust
COPY crates/trigger-supervisor crates/trigger-supervisor
COPY apps/gateway apps/gateway
COPY apps/endpoint-client apps/endpoint-client
COPY apps/tool-host apps/tool-host
COPY tool-sdk/cli tool-sdk/cli
RUN --mount=type=cache,id=toolgate-gateway-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=toolgate-package-cli-musl,target=/src/target,sharing=locked \
    cargo build --release --locked -p toolgate-cli && cp target/release/toolgate /usr/local/bin/toolgate
FROM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1
RUN apt-get update && apt-get install -y --no-install-recommends python3 git unzip curl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=cli /usr/local/bin/toolgate /usr/local/bin/toolgate
ENV TOOLGATE_CLI=/usr/local/bin/toolgate
WORKDIR /workspace
ENTRYPOINT ["python3","profiles/build.py"]
