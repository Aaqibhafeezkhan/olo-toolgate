# syntax=docker/dockerfile:1.7
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# OLO Orgs in one container for a laptop: the site image with SQLite, with the database, files and generated
# Drupal secrets on one volume (/var/lib/olo-orgs). The helper stays its own container (compose.yaml), and the signing
# key only ever goes into the helper. Not for production: there, use deploy/ (stateless replicas, external database).
#
#   docker build -t olo-orgs-standalone:local --build-arg SITE_IMAGE=olo-orgs-marketplace-drupal:local standalone

ARG SITE_IMAGE=olo-orgs-marketplace-drupal:local
FROM ${SITE_IMAGE}
USER root
# SQLite instead of an external PostgreSQL: the database driver is a module, so config/sync's core.extension names it.
RUN set -eux; \
    chmod u+w /opt/drupal/config/sync/core.extension.yml; \
    sed -i 's/^  pgsql: 0$/  sqlite: 0/' /opt/drupal/config/sync/core.extension.yml; \
    grep -q '^  sqlite: 0$' /opt/drupal/config/sync/core.extension.yml; \
    chmod a-w /opt/drupal/config/sync/core.extension.yml; \
    chmod u+w /opt/drupal/web/sites/default; \
    ln -s /var/lib/olo-orgs/public /opt/drupal/web/sites/default/files; \
    chmod a-w /opt/drupal/web/sites/default
COPY --chmod=0755 entrypoint.sh /usr/local/bin/olo-orgs-standalone
ENV MARKETPLACE_DB_DRIVER=sqlite \
    MARKETPLACE_DB_NAME=/var/lib/olo-orgs/db/site.sqlite \
    DRUPAL_HASH_SALT_FILE=/var/lib/olo-orgs/secrets/hash_salt \
    MARKETPLACE_HELPER_TOKEN_FILE=/var/lib/olo-orgs/secrets/helper_token \
    MARKETPLACE_ADMIN_PASSWORD_FILE=/var/lib/olo-orgs/secrets/admin_password \
    MARKETPLACE_PRIVATE_PATH=/var/lib/olo-orgs/private \
    MARKETPLACE_HELPER_FILE_ROOT=/var/lib/olo-orgs/private \
    MARKETPLACE_HELPER_URL=http://helper:8080 \
    MARKETPLACE_ID=olo-labs-marketplace \
    MARKETPLACE_BASE_URL=http://localhost:8080 \
    MARKETPLACE_TRUSTED_HOSTS=localhost,127.0.0.1 \
    MARKETPLACE_SEED_EXAMPLES=true \
    MARKETPLACE_ADMIN_MAIL=admin@example.com \
    MARKETPLACE_MAIL_FROM=noreply@example.com
VOLUME /var/lib/olo-orgs
ENTRYPOINT ["/usr/local/bin/olo-orgs-standalone"]
CMD []
