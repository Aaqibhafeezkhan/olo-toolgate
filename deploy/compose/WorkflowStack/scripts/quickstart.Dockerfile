# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
ARG QUICKSTART_IMAGE=olo-toolgate-quickstart:profile-stack
FROM ${QUICKSTART_IMAGE}
USER root
RUN install -d -o 0 -g 0 -m 0755 /opt/toolgate/folder-profile /opt/toolgate/folder-core
COPY --from=package --chown=0:0 --chmod=0644 workflow-bootstrap.json /opt/toolgate/folder-profile/trigger.json
COPY --from=package --chown=0:0 --chmod=0644 core-packages.lock.json inbox-watch.tgpkg /opt/toolgate/folder-core/
USER 65532:65532
ENV TOOLGATE_CONTROL_PROFILE_PATH=/opt/toolgate/folder-profile/trigger.json \
    TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH=/opt/toolgate/folder-core/core-packages.lock.json
