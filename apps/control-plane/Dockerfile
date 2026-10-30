# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG ADMIN_UI_SOURCE=console-build
FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS package-cli
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY packages/tenant-identity/rust packages/tenant-identity/rust
COPY apps/gateway/Cargo.toml apps/gateway/Cargo.toml
COPY apps/gateway/src apps/gateway/src
COPY apps/gateway/benches apps/gateway/benches
COPY apps/endpoint-client/Cargo.toml apps/endpoint-client/Cargo.toml
COPY apps/endpoint-client/src apps/endpoint-client/src
COPY apps/tool-host/Cargo.toml apps/tool-host/Cargo.toml
COPY apps/tool-host/src apps/tool-host/src
COPY tool-sdk/cli tool-sdk/cli
COPY tools/gateway/collect-notices.sh collect-notices.sh
RUN --mount=type=cache,id=toolgate-gateway-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=toolgate-package-cli-musl,target=/src/target,sharing=locked \
    cargo build --release --locked -p toolgate-cli && cp target/release/toolgate /usr/local/bin/toolgate && \
    sh collect-notices.sh /licenses

FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS console-build
WORKDIR /workspace
COPY package.json package-lock.json VERSION LICENSE ./
COPY packages/contracts/typescript/ packages/contracts/typescript/
COPY packages/contracts/VERSION packages/contracts/VERSION
COPY apps/admin-ui/ apps/admin-ui/
COPY config/initial/ config/initial/
COPY tests/fixtures/contracts/v1/valid.json tests/fixtures/contracts/v1/valid.json
COPY tools/ui/build.mjs tools/ui/build.mjs
ARG BUILD_NUMBER=local
ENV TOOLGATE_BUILD=$BUILD_NUMBER
RUN npm ci --ignore-scripts && npm run ui:build && node tools/ui/build.mjs --metadata-only

# CI consumes the UI already checked and packaged by this run's foundation job.
FROM scratch AS console-prebuilt
COPY apps/admin-ui/dist/ /workspace/apps/admin-ui/dist/

FROM ${ADMIN_UI_SOURCE} AS console

FROM eclipse-temurin:21-jdk-noble@sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be AS build
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends unzip python3 && rm -rf /var/lib/apt/lists/*
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties VERSION ./
COPY gradle/ gradle/
COPY packages/contracts/ packages/contracts/
COPY backend-libs/package-archive/ backend-libs/package-archive/
COPY backend-libs/service-authorization/ backend-libs/service-authorization/
COPY apps/control-plane/ apps/control-plane/
COPY config/initial/ config/initial/
COPY --from=console /workspace/apps/admin-ui/dist/ apps/admin-ui/dist/
# Gradle configures every project included in settings, including the SDK modules.
COPY tool-sdk/java-sdk/ tool-sdk/java-sdk/
COPY tool-sdk/json-sdk/ tool-sdk/json-sdk/
COPY tool-sdk/python-sdk/ tool-sdk/python-sdk/
COPY core-mcp/ core-mcp/
COPY --from=package-cli /usr/local/bin/toolgate /usr/local/bin/toolgate
ARG QUARKUS_PROFILE=prod
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && \
    ./gradlew --no-daemon -PprebuiltAdminUi=true -Dquarkus.profile=${QUARKUS_PROFILE} :control-plane:quarkusBuild :sdk-json:installDist && \
    python3 core-mcp/build.py --cli /usr/local/bin/toolgate

# Builder compiles canonical SDK projects; its fixed toolchain requires javac.
FROM eclipse-temurin:21-jdk-noble@sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="OLO ToolGate Control Plane" \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION
# The pinned base predates the Ubuntu fix for CVE-2026-111497; images built on Control inherit it.
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade libbrotli1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ARG CLIENT_ASSETS_DIR=deploy/client-assets/empty
ARG CLIENT_DOWNLOADS_DIRECTORY=""
COPY --chown=65532:65532 ${CLIENT_ASSETS_DIR}/ /opt/toolgate/client-downloads/
ENV TOOLGATE_CLIENT_DOWNLOADS_DIRECTORY=$CLIENT_DOWNLOADS_DIRECTORY
COPY --from=build --chown=65532:65532 /workspace/apps/control-plane/build/quarkus-app/ /app/
COPY --from=build /workspace/tool-sdk/json-sdk/build/install/toolgate-json-sdk/ /opt/toolgate/json-sdk/
COPY --from=build /workspace/build/core-mcp/ /opt/toolgate/core-mcp/
COPY --from=package-cli /usr/local/bin/toolgate /usr/local/bin/toolgate
COPY --from=package-cli /licenses /usr/share/licenses/olo-toolgate/package-cli/
COPY LICENSE apps/control-plane/NOTICE.md /usr/share/licenses/olo-toolgate/
COPY apps/control-plane/gradle.lockfile /usr/share/licenses/olo-toolgate/third-party/gradle.lockfile
COPY tools/dependency-license-reviews.json /usr/share/licenses/olo-toolgate/third-party/
COPY package-lock.json /usr/share/licenses/olo-toolgate/third-party/package-lock.json
COPY LICENSE NOTICE.md /usr/share/licenses/olo-toolgate/
COPY RELEASE-NOTES.md /usr/share/doc/olo-toolgate/RELEASE-NOTES.md
USER 65532:65532
EXPOSE 8082 9092
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
