# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS builder
WORKDIR /src
ARG VERSION
ARG TARGETARCH
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY apps/gateway/Cargo.toml apps/gateway/Cargo.toml
COPY apps/gateway/src apps/gateway/src
COPY apps/gateway/benches apps/gateway/benches
COPY apps/endpoint-client/Cargo.toml apps/endpoint-client/Cargo.toml
COPY apps/endpoint-client/src apps/endpoint-client/src
COPY apps/tool-host/Cargo.toml apps/tool-host/Cargo.toml
COPY apps/tool-host/src apps/tool-host/src
COPY tool-sdk/cli tool-sdk/cli
# The CLI embeds the community guide for `toolgate guide`.
COPY TOOL-SDK-GUIDE.md TOOL-SDK-GUIDE.md
RUN --mount=type=cache,id=toolgate-gateway-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=toolgate-gateway-musl-${TARGETARCH},target=/src/target,sharing=locked \
    test "$VERSION" = "$(cat VERSION)" && cargo build --release --locked -p olo-toolgate-gateway --bin olo-toolgate-gateway && \
    cp /src/target/release/olo-toolgate-gateway /gateway
COPY tools/gateway/collect-notices.sh /src/collect-notices.sh
RUN --mount=type=cache,id=toolgate-gateway-registry,target=/usr/local/cargo/registry \
    sh /src/collect-notices.sh /licenses

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
ARG VERSION
ARG REVISION=unknown
LABEL org.opencontainers.image.title="OLO ToolGate Gateway" \
      org.opencontainers.image.description="Stateless fail-closed runtime authorization gateway" \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION"
COPY --from=builder --chown=65532:65532 /gateway /usr/local/bin/olo-toolgate-gateway
COPY --chown=65532:65532 LICENSE /usr/share/licenses/olo-toolgate/LICENSE
COPY --from=builder --chown=65532:65532 /licenses /usr/share/licenses/olo-toolgate/third-party
COPY LICENSE NOTICE.md /usr/share/licenses/olo-toolgate/
COPY RELEASE-NOTES.md /usr/share/doc/olo-toolgate/RELEASE-NOTES.md
USER 65532:65532
EXPOSE 8081 9091
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/olo-toolgate-gateway"]
