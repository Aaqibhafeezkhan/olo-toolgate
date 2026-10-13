# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG DEVICE_IMAGE=ololab/olo-toolgate-device:dev
FROM ${DEVICE_IMAGE}
LABEL org.opencontainers.image.title="OLO ToolGate Linux secured device" \
      org.opencontainers.image.description="Native device with Gateway-only network access"
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nftables util-linux && \
    rm -rf /var/lib/apt/lists/*
COPY apps/device/secured_network.py apps/device/secured_runtime.py /opt/toolgate/
ENTRYPOINT ["python", "-u", "/opt/toolgate/secured_network.py"]
