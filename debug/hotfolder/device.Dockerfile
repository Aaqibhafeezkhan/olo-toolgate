# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
FROM olo-toolgate-debug-device:hotfolder
# Reuse the real source-built debug device and the existing signed-package demo adapter.
COPY --from=docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb /usr/local/bin/docker /usr/bin/docker
COPY examples/langchain/device.py /opt/toolgate/langchain-device.py
COPY examples/langchain/fixtures /opt/toolgate/fixtures
RUN ln -s /usr/bin/python3 /usr/local/bin/python && \
    ln -s device-entrypoint.py /opt/toolgate/device_entrypoint.py
