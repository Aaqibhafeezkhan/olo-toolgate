# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG DEVICE_IMAGE=ololab/olo-toolgate-device:dev
FROM ${DEVICE_IMAGE}
LABEL org.opencontainers.image.title="OLO ToolGate Linux secured device" \
      org.opencontainers.image.description="Native device with Gateway-only network access"
# A supplied device base must already contain the fixed APT client/library.
RUN dpkg --compare-versions "$(dpkg-query -W -f='${Version}' apt)" ge 2.8.4ubuntu0.1 && \
    dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libapt-pkg6.0t64)" ge 2.8.4ubuntu0.1 && \
    apt-get -o Acquire::Retries=0 update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nftables util-linux && \
    rm -rf /var/lib/apt/lists/*
COPY apps/device/secured_network.py apps/device/secured_runtime.py apps/device/device_execution.py /opt/toolgate/
ENTRYPOINT ["python", "-u", "/opt/toolgate/secured_network.py"]
