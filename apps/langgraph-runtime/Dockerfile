# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
ARG PYTHON_IMAGE=python:3.12-alpine@sha256:c5ba3deef00c7758561a0f03c26f44f10589263d40e732b8748e0d1f38f6dd89
FROM ${PYTHON_IMAGE} AS base
RUN apk upgrade --no-cache

FROM base AS dependencies
WORKDIR /app
COPY requirements.lock ./
RUN python -m venv /opt/toolgate-runtime \
    && /opt/toolgate-runtime/bin/python -m pip install --no-cache-dir -r requirements.lock \
    && /opt/toolgate-runtime/bin/python -m pip check

FROM base
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="OLO ToolGate LangGraph runtime" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.source="https://github.com/olo-labs/olo-toolgate" \
      org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /app
COPY --from=dependencies /opt/toolgate-runtime /opt/toolgate-runtime
COPY src/ ./src/
ENV PYTHONPATH=/app/src PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
USER 65532:65532
ENTRYPOINT ["/opt/toolgate-runtime/bin/python", "-m", "toolgate_runtime.worker"]
