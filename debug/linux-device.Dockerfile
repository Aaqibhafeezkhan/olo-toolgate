# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
FROM rust:1.94.1-bookworm@sha256:6ae102bdbf528294bc79ad6e1fae682f6f7c2a6e6621506ba959f9685b308a55 AS builder
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY apps/gateway/Cargo.toml apps/gateway/Cargo.toml
COPY apps/gateway/src apps/gateway/src
COPY apps/gateway/benches apps/gateway/benches
COPY apps/endpoint-client apps/endpoint-client
COPY apps/tool-host apps/tool-host
COPY tool-sdk/cli tool-sdk/cli
RUN --mount=type=cache,id=toolgate-devices-registry,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,id=toolgate-devices-target,target=/src/target,sharing=locked \
    cargo build --release --locked -p olo-toolgate-client --bin olo-toolgate-client && \
    cp target/release/olo-toolgate-client /client

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /etc/systemd/system /etc/olo-toolgate /var/lib/olo-toolgate /usr/local/lib/olo-toolgate \
    && chmod 700 /var/lib/olo-toolgate
COPY --from=builder /client /opt/toolgate/client
COPY debug/device-entrypoint.py /opt/toolgate/device-entrypoint.py
COPY debug/device-systemctl.sh /usr/bin/systemctl
COPY LICENSE NOTICE.md /usr/share/licenses/olo-toolgate/
RUN chmod 755 /opt/toolgate/client /usr/bin/systemctl
LABEL org.opencontainers.image.title="ToolGate Linux debug device" \
      org.opencontainers.image.licenses="Apache-2.0"
STOPSIGNAL SIGTERM
ENTRYPOINT ["python3", "-u", "/opt/toolgate/device-entrypoint.py"]
