# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The selected Quickstart with the hot-folder deployment profile and its sealed core lock baked in (compose.triggers.yaml).
# Control reads both only as root-owned files that local writers cannot change, as for the image's own profile and lock,
# so they are copied in rather than bind-mounted from the host. The `package` context is `manage.py prepare`'s output.
ARG QUICKSTART_IMAGE=ololab/olo-toolgate-quickstart:dev
FROM ${QUICKSTART_IMAGE}
USER root
RUN install -d -o 0 -g 0 -m 0755 /opt/toolgate/example /opt/toolgate/example-core
COPY --chown=0:0 --chmod=0644 hotfolder-trigger.profile.json /opt/toolgate/example/hotfolder-trigger.profile.json
COPY --from=package --chown=0:0 --chmod=0644 core-packages.lock.json inbox-watch.tgpkg /opt/toolgate/example-core/
USER 65532:65532
ENV TOOLGATE_CONTROL_PROFILE_PATH=/opt/toolgate/example/hotfolder-trigger.profile.json \
    TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH=/opt/toolgate/example-core/core-packages.lock.json
