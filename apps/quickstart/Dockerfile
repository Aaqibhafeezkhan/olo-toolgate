# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Build Control from its existing Dockerfile under this tag before composition.
ARG CONTROL_IMAGE=olo-toolgate-control:module11
FROM golang:1.27.4-alpine@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280 AS registry-build
WORKDIR /src
# Official v3.1.2 source, checksum-bound; its published binary still has affected
# Go/dependency versions. Patch only the identified modules and retain checksums.
ADD --checksum=sha256:2f9b6fc00bec854e62e9b720903a9c9b48018505177f7c7a8ce7b74da2b23e4f https://codeload.github.com/distribution/distribution/tar.gz/refs/tags/v3.1.2 /tmp/distribution.tar.gz
RUN tar -xzf /tmp/distribution.tar.gz --strip-components=1 -C /src && rm /tmp/distribution.tar.gz
ENV GOTOOLCHAIN=local CGO_ENABLED=0
RUN --mount=type=cache,id=toolgate-registry-go-modules,target=/go/pkg/mod \
    --mount=type=cache,id=toolgate-registry-go-build,target=/root/.cache/go-build \
    go get github.com/go-jose/go-jose/v4@v4.1.6 github.com/klauspost/compress@v1.18.7 golang.org/x/net@v0.62.0 && \
    go build -mod=mod -trimpath -ldflags='-s -w -X github.com/distribution/distribution/v3/version.version=v3.1.2-olo.1' -o /registry ./cmd/registry && \
    mkdir -p /registry-licenses && cp LICENSE go.mod go.sum /registry-licenses/ && \
    go list -mod=mod -m -f '{{.Path}} {{.Dir}}' all > /registry-licenses/modules.txt && \
    while read -r module directory; do \
      test -n "$directory" || continue; mkdir -p "/registry-licenses/$module"; \
      for notice in "$directory"/LICENSE* "$directory"/COPYING* "$directory"/NOTICE*; do \
        if test -f "$notice"; then cp "$notice" "/registry-licenses/$module/"; fi; \
      done; \
    done < /registry-licenses/modules.txt

FROM rust:1.94.1-alpine@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS rust-build
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml VERSION TOOL-SDK-GUIDE.md ./
COPY packages/contracts/rust packages/contracts/rust
COPY packages/tenant-identity/rust packages/tenant-identity/rust
COPY crates/trigger-supervisor crates/trigger-supervisor
COPY apps/gateway apps/gateway
COPY apps/endpoint-client apps/endpoint-client
COPY apps/tool-host apps/tool-host
COPY tool-sdk/cli tool-sdk/cli
# The CLI embeds the community guide for `toolgate guide`.
COPY TOOL-SDK-GUIDE.md TOOL-SDK-GUIDE.md
COPY tools/gateway/collect-notices.sh /src/collect-notices.sh
RUN --mount=type=cache,id=toolgate-gateway-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=toolgate-quickstart-musl,target=/src/target,sharing=locked \
    find apps/gateway/src apps/endpoint-client/src apps/tool-host/src tool-sdk/cli/src packages/contracts/rust/src packages/tenant-identity/rust/src -type f -exec touch {} + && \
    cargo build --release --locked -p olo-toolgate-gateway --bin olo-toolgate-gateway -p olo-toolgate-tool-host --bin olo-toolgate-tool-host && \
    cp target/release/olo-toolgate-gateway target/release/olo-toolgate-tool-host /usr/local/bin/ && \
    sh /src/collect-notices.sh /licenses

FROM ${CONTROL_IMAGE}
USER root
COPY --from=registry-build /registry /usr/local/bin/distribution
COPY --from=registry-build /registry-licenses /usr/share/licenses/olo-toolgate/registry/
COPY --from=docker:29.10.0-cli@sha256:34507eb5069d8be82c95dd3b6987d4250fd5fee49b82e2509b7d7a7030740d6d /usr/local/bin/docker /usr/bin/docker
COPY apps/quickstart/requirements.txt /opt/quickstart-requirements.txt
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv libpq5 && \
    python3 -m venv /opt/quickstart-python && /opt/quickstart-python/bin/pip install --no-cache-dir -r /opt/quickstart-requirements.txt && \
    rm -rf /var/lib/apt/lists/* && mkdir -p /data /trust /usr/local/lib/toolgate && chown 65532:65532 /data /trust && chmod 0700 /data /trust
# JNI is loaded from immutable image storage, keeping /tmp compatible with noexec.
RUN /opt/quickstart-python/bin/python -c "import glob,platform,zipfile; p=glob.glob('/app/lib/main/org.xerial.sqlite-jdbc-*.jar')[0]; z=zipfile.ZipFile(p); a={'x86_64':'x86_64','aarch64':'aarch64'}[platform.machine()]; open('/usr/local/lib/toolgate/libsqlitejdbc.so','wb').write(z.read('org/sqlite/native/Linux/'+a+'/libsqlitejdbc.so'))"
COPY tools/quickstart/python_notices.py /opt/quickstart-python-notices.py
RUN /opt/quickstart-python/bin/python /opt/quickstart-python-notices.py
COPY --from=rust-build /usr/local/bin/olo-toolgate-gateway /usr/local/bin/olo-toolgate-tool-host /usr/local/bin/
COPY --from=rust-build /licenses /usr/share/licenses/olo-toolgate/quickstart-rust/
COPY tools/enterprise/restore.py /opt/quickstart/enterprise_restore.py
COPY deploy/compose/WorkflowStack/scripts/bootstrap.py deploy/compose/WorkflowStack/scripts/identity.py /opt/toolgate/workflow-stack/
COPY examples/langchain/manage.py examples/langchain/packages.py /opt/toolgate/workflow-stack/operator/examples/langchain/
COPY debug/profiled/configure-debug-agent.py debug/profiled/agent-mimic.py /opt/toolgate/workflow-stack/operator/debug/profiled/
COPY tools/access/presets.py /opt/toolgate/workflow-stack/operator/tools/access/
COPY config/initial/ /opt/toolgate/workflow-stack/operator/config/initial/
COPY packages/contracts/management-permissions.json /opt/toolgate/workflow-stack/operator/packages/contracts/management-permissions.json
COPY VERSION /opt/toolgate/workflow-stack/operator/VERSION
COPY apps/quickstart/workflow_installation.py apps/quickstart/supervisor.py apps/quickstart/enterprise_bootstrap.py apps/quickstart/audit_chain.py VERSION /opt/quickstart/
COPY packages/contracts/management-permissions.json /opt/quickstart/management-permissions.json
# The default deployment profile; a deployment selects another with TOOLGATE_CONTROL_PROFILE_PATH.
COPY deploy/profiles/fresh.json /opt/toolgate/profiles/fresh.json
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
    TOOLGATE_CONTROL_PROFILE_PATH=/opt/toolgate/profiles/fresh.json \
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
