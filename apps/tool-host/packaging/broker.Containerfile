# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
# Trusted static helper and public roots only. No workload identity, runtime socket or shell.
FROM scratch
COPY broker /usr/local/bin/olo-toolgate-invocation-broker
COPY ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY LICENSE NOTICE.md /usr/share/doc/toolgate-broker/
COPY work /work/
USER 100:100
ENTRYPOINT ["/usr/local/bin/olo-toolgate-invocation-broker", "--invocation-broker"]
