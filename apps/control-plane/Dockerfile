# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG ADMIN_UI_SOURCE=console-build
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
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties VERSION ./
COPY gradle/ gradle/
COPY packages/contracts/ packages/contracts/
COPY apps/control-plane/ apps/control-plane/
COPY config/initial/ config/initial/
COPY --from=console /workspace/apps/admin-ui/dist/ apps/admin-ui/dist/
COPY apps/marketplace-api/build.gradle.kts apps/marketplace-api/build.gradle.kts
COPY apps/marketplace-worker/build.gradle.kts apps/marketplace-worker/build.gradle.kts
# Gradle configures every project included in settings, including the SDK modules.
COPY tool-sdk/java-sdk/ tool-sdk/java-sdk/
ARG QUARKUS_PROFILE=prod
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon -PprebuiltAdminUi=true -Dquarkus.profile=${QUARKUS_PROFILE} :control-plane:quarkusBuild

FROM eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="OLO ToolGate Control Plane" \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION
WORKDIR /app
ARG CLIENT_ASSETS_DIR=deploy/client-assets/empty
ARG CLIENT_DOWNLOADS_DIRECTORY=""
COPY --chown=65532:65532 ${CLIENT_ASSETS_DIR}/ /opt/toolgate/client-downloads/
ENV TOOLGATE_CLIENT_DOWNLOADS_DIRECTORY=$CLIENT_DOWNLOADS_DIRECTORY
COPY --from=build --chown=65532:65532 /workspace/apps/control-plane/build/quarkus-app/ /app/
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
