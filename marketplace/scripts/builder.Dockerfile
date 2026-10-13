# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# A Linux shell for scripts/build-images.sh on hosts without bash or PHP (debug/manage.ps1 on Windows): Composer's image
# brings bash, GNU tar, PHP and Composer; the Docker CLI with buildx talks to the host's engine through its socket.
FROM docker:28.5.1-cli AS docker
FROM composer:2.10.3
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx
ENTRYPOINT []
