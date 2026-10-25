# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG QUICKSTART_IMAGE=ololab/olo-toolgate-quickstart:dev
FROM ${QUICKSTART_IMAGE} AS cli
FROM eclipse-temurin:21-jdk-noble@sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git && rm -rf /var/lib/apt/lists/*
COPY --from=cli /usr/local/bin/toolgate /usr/local/bin/toolgate
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties VERSION TOOL-SDK-GUIDE.md ./
COPY gradle/ gradle/
COPY packages/contracts/ packages/contracts/
COPY backend-libs/package-archive/ backend-libs/package-archive/
COPY backend-libs/service-authorization/ backend-libs/service-authorization/
COPY config/initial/ config/initial/
COPY apps/control-plane/build.gradle.kts apps/control-plane/build.gradle.kts
COPY apps/marketplace-api/build.gradle.kts apps/marketplace-api/build.gradle.kts
COPY apps/marketplace-worker/build.gradle.kts apps/marketplace-worker/build.gradle.kts
COPY tool-sdk/java-sdk/ tool-sdk/java-sdk/
COPY tool-sdk/json-sdk/ tool-sdk/json-sdk/
COPY examples/java-tools/ examples/java-tools/
RUN --mount=type=cache,id=toolgate-langchain-gradle,target=/root/.gradle \
    chmod +x gradlew examples/java-tools/gradlew && \
    ./gradlew --no-daemon --configure-on-demand publishJavaSdkLocally && \
    cd examples/java-tools && ./gradlew --no-daemon -PtoolgateSdkRepo=/src/build/sdk-repo :hotfolder:toolgatePackage
FROM scratch
COPY --from=build /src/examples/java-tools/hotfolder/build/toolgate/hotfolder-0.1.0.tgpkg /hotfolder.tgpkg
