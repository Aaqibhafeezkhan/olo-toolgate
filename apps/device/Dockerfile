# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG QUICKSTART_IMAGE=ololab/olo-toolgate-quickstart:dev
FROM ${QUICKSTART_IMAGE} AS release
FROM python:3.14-slim AS extract
ARG TARGETARCH
WORKDIR /work
COPY --from=release /opt/toolgate/client-downloads/manifest.json /work/assets/
COPY --from=release /opt/toolgate/client-downloads/*-unknown-linux-gnu.tar.gz /work/assets/
COPY --from=release /opt/quickstart/VERSION /work/VERSION
COPY tools/client/extract.py tools/client/package.py /work/tools/client/
RUN case "$TARGETARCH" in amd64) target=x86_64-unknown-linux-gnu ;; arm64) target=aarch64-unknown-linux-gnu ;; *) exit 1 ;; esac && \
    python tools/client/extract.py --source /work/assets --target "$target" --output /opt/toolgate/client
# Match the native GNU client's glibc baseline and install only its supervisor.
FROM ubuntu:24.04
ARG VERSION
RUN test -n "$VERSION"
ARG REVISION=unknown
LABEL org.opencontainers.image.title="OLO ToolGate Linux device" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 ca-certificates && \
    rm -rf /var/lib/apt/lists/* && ln -s /usr/bin/python3 /usr/local/bin/python && \
    mkdir -p /etc/systemd/system /etc/olo-toolgate /var/lib/olo-toolgate /usr/local/lib/olo-toolgate && \
    ln -s /usr/local/lib/olo-toolgate/olo-toolgate-client /usr/local/bin/olo-toolgate-client && \
    chmod 700 /var/lib/olo-toolgate
COPY --from=extract /opt/toolgate/client /opt/toolgate/client
# The container engine CLI the client drives for signed package tools (ADR 026); the engine itself runs elsewhere and is
# reached only through the socket a deployment mounts. Root-owned, as the client requires of its engine binary.
COPY --from=docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb /usr/local/bin/docker /usr/bin/docker
COPY debug/device-entrypoint.py /opt/toolgate/device_entrypoint.py
COPY debug/device-systemctl.sh /usr/bin/systemctl
COPY apps/device/entrypoint.py /opt/toolgate/device.py
COPY examples/langchain/device.py /opt/toolgate/langchain-device.py
COPY examples/langchain/fixtures /opt/toolgate/fixtures
COPY LICENSE NOTICE.md /usr/share/licenses/olo-toolgate/
COPY --from=release /usr/share/licenses/olo-toolgate/quickstart-rust/ /usr/share/licenses/olo-toolgate/client-rust/
COPY RELEASE-NOTES.md /usr/share/doc/olo-toolgate/RELEASE-NOTES.md
RUN chmod 755 /opt/toolgate/client /usr/bin/systemctl
ENV PYTHONDONTWRITEBYTECODE=1
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import json,time; d=json.load(open('/run/olo-toolgate/device-status.json')); assert time.time()*1000-d['updatedAtUnixMs'] < 120000"
STOPSIGNAL SIGTERM
ENTRYPOINT ["python", "-u", "/opt/toolgate/device.py"]
