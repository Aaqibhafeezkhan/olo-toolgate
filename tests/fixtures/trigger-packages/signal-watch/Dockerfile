# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
# Test/package authoring only; this image is never part of WorkflowStack.
FROM toolgate-workflow-sdk-fixture:current AS build
COPY tests/fixtures/trigger-packages/signal-watch/ /fixture/
RUN --mount=type=cache,target=/root/.gradle cd /workspace/examples/java-tools && \
    ./gradlew --no-daemon -PtoolgateSdkRepo=/workspace/build/sdk-repo :hotfolder-watch:check
RUN --mount=type=cache,target=/root/.gradle cd /fixture && \
    /workspace/gradlew --no-daemon -PtoolgateSdkRepo=/workspace/build/sdk-repo \
    -PtoolgateSdkVersion=$(cat /workspace/VERSION) toolgatePackage
FROM scratch
COPY --from=build /fixture/build/toolgate/signal-watch-0.1.0.olotool /signal-watch.olotool
