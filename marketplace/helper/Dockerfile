# syntax=docker/dockerfile:1.7
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# olo-toolgate-marketplace-helper: validates and signs .tgpkg packages for the OLO Orgs Marketplace.
#
# Build context: ${TOOLGATE_DIR} (the ToolGate checkout, resolved by scripts/toolgate-dir.sh).
# Named context "marketplace": ${MARKETPLACE_DIR}. Built by scripts/compose.sh and ci/publish-images.sh:
#
#   docker build -f "$MARKETPLACE_DIR/helper/Dockerfile" --build-context marketplace="$MARKETPLACE_DIR" \
#     --build-arg TOOLGATE_REVISION="$TOOLGATE_REVISION" "$TOOLGATE_DIR"
#
# The helper uses only the JDK and the unchanged toolgate CLI; nothing is downloaded from Maven Central.

# The unchanged ToolGate CLI, built statically (musl), exactly as the Quickstart image builds its Rust binaries.
FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS cli
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY packages/contracts/tools packages/contracts/tools
COPY apps/gateway apps/gateway
COPY apps/endpoint-client apps/endpoint-client
COPY apps/tool-host apps/tool-host
COPY tool-sdk/cli tool-sdk/cli
# An optional build secret "ca" adds a CA bundle for builds behind a TLS-intercepting proxy (scripts/build-images.sh).
RUN --mount=type=secret,id=ca,required=false,target=/run/secrets/ca \
    --mount=type=cache,id=marketplace-cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=marketplace-cli-target,target=/src/target,sharing=locked \
    if [ -s /run/secrets/ca ]; then export SSL_CERT_FILE=/run/secrets/ca CARGO_HTTP_CAINFO=/run/secrets/ca; fi && \
    cargo build --release --locked -p toolgate-cli --bin toolgate && \
    install -m 0555 target/release/toolgate /toolgate

# The helper: plain javac and jar, no build tool and no repositories.
FROM eclipse-temurin:21-jdk-noble@sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be AS helper
WORKDIR /src
COPY --from=marketplace helper/src/main/java /src/java
RUN find /src/java -name '*.java' >/tmp/sources && \
    javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d /classes @/tmp/sources && \
    jar --create --file /marketplace-helper.jar --main-class io.ololabs.marketplace.helper.Main -C /classes .

FROM eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766
ARG TOOLGATE_REVISION=unknown
LABEL org.opencontainers.image.title="olo-toolgate-marketplace-helper" \
      org.opencontainers.image.description="Validates and signs ToolGate packages for the OLO Orgs Marketplace" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      io.ololabs.toolgate.revision="${TOOLGATE_REVISION}"
COPY --from=cli /toolgate /opt/olo/bin/toolgate
COPY --from=helper /marketplace-helper.jar /opt/olo/marketplace-helper.jar
ENV TOOLGATE_CLI=/opt/olo/bin/toolgate \
    TOOLGATE_REVISION=${TOOLGATE_REVISION} \
    MARKETPLACE_HELPER_PORT=8080 \
    MARKETPLACE_SIGNING_KEY_FILE=/run/secrets/marketplace_signing_key \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
# Non-root; run with a read-only root filesystem and a small tmpfs at /tmp (Compose and Helm do).
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD ["java", "-XX:TieredStopAtLevel=1", "-cp", "/opt/olo/marketplace-helper.jar", "io.ololabs.marketplace.helper.HealthCheck"]
ENTRYPOINT ["java", "-jar", "/opt/olo/marketplace-helper.jar"]
