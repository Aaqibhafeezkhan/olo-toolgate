# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
ARG QUICKSTART_IMAGE=olo-toolgate-quickstart:profile-stack
FROM ${QUICKSTART_IMAGE}
USER root
COPY --from=docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb /usr/local/bin/docker /usr/bin/docker
COPY deploy/compose/WorkflowStack/scripts/identity.py /deployment/identity.py
ENTRYPOINT ["/opt/quickstart-python/bin/python", "-u", "/deployment/identity.py"]
