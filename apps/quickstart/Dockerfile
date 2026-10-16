# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Build Control from its existing Dockerfile under this tag before composition.
ARG CONTROL_IMAGE=olo-toolgate-control:module11
FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS rust-build
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY packages/contracts/tools packages/contracts/tools
COPY apps/gateway apps/gateway
COPY apps/endpoint-client apps/endpoint-client
COPY apps/tool-host apps/tool-host
COPY tool-sdk/cli tool-sdk/cli
# The CLI embeds the community guide for `toolgate guide`.
COPY TOOL-SDK-GUIDE.md TOOL-SDK-GUIDE.md
COPY tools/gateway/collect-notices.sh /src/collect-notices.sh
RUN --mount=type=cache,id=toolgate-gateway-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=toolgate-quickstart-musl,target=/src/target,sharing=locked \
    find apps/gateway/src apps/endpoint-client/src apps/tool-host/src tool-sdk/cli/src packages/contracts/rust/src -type f -exec touch {} + && \
    cargo build --release --locked -p olo-toolgate-gateway --bin olo-toolgate-gateway -p olo-toolgate-client --bin quickstart-tools -p olo-toolgate-tool-host --bin olo-toolgate-tool-host && \
    cp target/release/olo-toolgate-gateway target/release/quickstart-tools target/release/olo-toolgate-tool-host /usr/local/bin/ && \
    sh /src/collect-notices.sh /licenses

FROM ${CONTROL_IMAGE}
USER root
COPY apps/quickstart/requirements.txt /opt/quickstart-requirements.txt
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv libpq5 && \
    python3 -m venv /opt/quickstart-python && /opt/quickstart-python/bin/pip install --no-cache-dir -r /opt/quickstart-requirements.txt && \
    rm -rf /var/lib/apt/lists/* && mkdir -p /data /trust /usr/local/lib/toolgate && chown 65532:65532 /data /trust && chmod 0700 /data /trust
# JNI is loaded from immutable image storage, keeping /tmp compatible with noexec.
RUN /opt/quickstart-python/bin/python -c "import glob,platform,zipfile; p=glob.glob('/app/lib/main/org.xerial.sqlite-jdbc-*.jar')[0]; z=zipfile.ZipFile(p); a={'x86_64':'x86_64','aarch64':'aarch64'}[platform.machine()]; open('/usr/local/lib/toolgate/libsqlitejdbc.so','wb').write(z.read('org/sqlite/native/Linux/'+a+'/libsqlitejdbc.so'))"
COPY tools/quickstart/python_notices.py /opt/quickstart-python-notices.py
RUN /opt/quickstart-python/bin/python /opt/quickstart-python-notices.py
COPY --from=rust-build /usr/local/bin/olo-toolgate-gateway /usr/local/bin/quickstart-tools /usr/local/bin/olo-toolgate-tool-host /usr/local/bin/
COPY --from=rust-build /licenses /usr/share/licenses/olo-toolgate/quickstart-rust/
COPY tools/enterprise/restore.py /opt/quickstart/enterprise_restore.py
COPY apps/quickstart/supervisor.py apps/quickstart/enterprise_bootstrap.py VERSION /opt/quickstart/
COPY packages/contracts/tools/builtins.json /opt/quickstart/builtins.json
COPY packages/contracts/management-permissions.json /opt/quickstart/management-permissions.json
COPY docs/getting-started/one-minute-quickstart.md docs/deployment/quickstart.md /usr/share/doc/toolgate-quickstart/
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="OLO ToolGate Quickstart" \
      org.opencontainers.image.description="Single-node non-HA evaluation: Gateway, Control, Tool Host and console" \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -Djava.io.tmpdir=/tmp -Dorg.sqlite.lib.path=/usr/local/lib/toolgate" \
    TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH=/opt/toolgate/core-mcp/core-packages.lock.json \
    PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY LICENSE NOTICE.md /usr/share/licenses/olo-toolgate/
COPY apps/quickstart/NOTICE.md /usr/share/licenses/olo-toolgate/quickstart-python-NOTICE.md
COPY RELEASE-NOTES.md /usr/share/doc/olo-toolgate/RELEASE-NOTES.md
USER 65532:65532
VOLUME ["/data"]
EXPOSE 8080 8443
HEALTHCHECK --interval=30s --timeout=4s --start-period=90s --retries=3 CMD ["/opt/quickstart-python/bin/python", "-c", "import urllib.request; r=urllib.request.urlopen('http://127.0.0.1:8080/health/ready',timeout=3); raise SystemExit(0 if r.status==200 else 1)"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/quickstart-python/bin/python", "/opt/quickstart/supervisor.py"]
