# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
# Disposable UI qualification only: current Control artifacts + an unoptimized current-source Gateway.
# Build context: ToolGate root; named control-app context: apps/control-plane/build/quarkus-app.
FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS gateway
WORKDIR /src
ENV CARGO_PROFILE_DEV_DEBUG=0 CARGO_PROFILE_TEST_DEBUG=0 CARGO_BUILD_JOBS=4 RUSTUP_TOOLCHAIN=1.94.1 CARGO_HTTP_TIMEOUT=45
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY packages/tenant-identity/rust packages/tenant-identity/rust
COPY apps/gateway apps/gateway
COPY apps/endpoint-client/Cargo.toml apps/endpoint-client/Cargo.toml
COPY apps/endpoint-client/src apps/endpoint-client/src
COPY apps/tool-host/Cargo.toml apps/tool-host/Cargo.toml
COPY apps/tool-host/src apps/tool-host/src
COPY tool-sdk/cli tool-sdk/cli
RUN --mount=type=cache,id=ui-tour-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=ui-tour-target,target=/src/target,sharing=locked \
    cargo test --locked -p olo-toolgate-gateway --lib http::trace_context::durable_conflicts_remain_client_conflicts && \
    cargo build --locked -p olo-toolgate-gateway -p olo-toolgate-tool-host -p toolgate-cli && \
    cp target/debug/olo-toolgate-gateway target/debug/olo-toolgate-tool-host target/debug/toolgate /usr/local/bin/

FROM ololab/olo-toolgate-quickstart@sha256:23b3a1b6275162027451a7ba923a58095838ce5a454bcaf02d0dccfaf81aeb19 AS inventory
USER root
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/*
COPY . .
COPY --from=gateway /usr/local/bin/toolgate /usr/local/bin/toolgate
RUN --mount=type=cache,id=ui-tour-gradle,target=/root/.gradle \
    find . -name gradlew -type f -exec sed -i 's/\r$//' {} + && \
    find . -name gradlew -type f -exec chmod +x {} + && \
    /opt/quickstart-python/bin/python core-mcp/build.py --cli /usr/local/bin/toolgate && \
    TOOLGATE_CLI=/usr/local/bin/toolgate ./gradlew -p examples/mocked-tools \
    -PtoolgateSdkRepo=file:///src/build/sdk-repo :server-recovery:check :server-recovery:toolgatePackage

# Main 4da79ab2: Tool Host, supervisor, core packages and SDK inputs are unchanged through 6732c00e.
FROM ololab/olo-toolgate-quickstart@sha256:23b3a1b6275162027451a7ba923a58095838ce5a454bcaf02d0dccfaf81aeb19
USER root
RUN rm -rf /app /opt/toolgate/core-mcp /opt/toolgate/json-sdk && mkdir /app && chown 65532:65532 /app
COPY --from=control-app --chown=65532:65532 / /app/
COPY --from=gateway /usr/local/bin/olo-toolgate-gateway /usr/local/bin/olo-toolgate-gateway
COPY --from=gateway /usr/local/bin/olo-toolgate-tool-host /usr/local/bin/olo-toolgate-tool-host
COPY --from=gateway /usr/local/bin/toolgate /usr/local/bin/toolgate
COPY --from=inventory --chown=65532:65532 /src/build/core-mcp/ /opt/toolgate/core-mcp/
COPY --from=inventory /src/tool-sdk/json-sdk/build/install/toolgate-json-sdk/ /opt/toolgate/json-sdk/
COPY --from=inventory /src/examples/mocked-tools/server-recovery/build/toolgate/server-recovery-0.1.0.tgpkg /opt/toolgate/ui-tour/server-recovery-0.1.0.tgpkg
LABEL io.olo.qualification.owner="olo-knowledge" io.olo.qualification.gateway-profile="dev"
USER 65532:65532
