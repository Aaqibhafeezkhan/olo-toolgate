# syntax=docker/dockerfile:1.7
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
#
# The complete OLO Orgs site image (implementation prompt §6.1): Drupal core and every module in modules.yml from the
# committed composer.lock, the toolgate_marketplace module, the olo_orgs theme, config/sync and the example packages.
# Nothing is downloaded or installed at runtime. One container serves HTTP on 8080 as www-data, with a read-only root
# filesystem; /tmp is the only writable path.
#
# Build context: ${MARKETPLACE_DIR}/drupal. Named contexts:
#   marketplace = ${MARKETPLACE_DIR}       (for seed/expected-packages.json)
#   seed        = the seed stage's output  (marketplace/seed/Dockerfile.stage, target "seed"; a directory holding seed/,
#                                           or the image Compose builds from that stage)
#
# Base: the official drupal image for Drupal 11.4 on PHP 8.4. The official 11.4 images are published for PHP 8.4 and
# 8.5 only; there is no PHP 8.3 variant for this release.

ARG DRUPAL_IMAGE=drupal:11.4.8-php8.4-apache-trixie@sha256:da8bd27ac8dd94c2a2bd68e8e1df6428fa511411ffbc59e27ac0d5482549fdc2
ARG COMPOSER_IMAGE=composer:2.10.3@sha256:af98f42dfff7c68ba8d53c2164fd9fde1087b7d449514baa38c418b1f6bc4bac

FROM ${COMPOSER_IMAGE} AS composer

FROM ${DRUPAL_IMAGE} AS runtime
# The redis extension for the shared cache; built here, never at runtime.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends $PHPIZE_DEPS; \
    pecl install redis-6.3.0; \
    docker-php-ext-enable redis; \
    apt-get purge -y --auto-remove $PHPIZE_DEPS; \
    rm -rf /var/lib/apt/lists/* /tmp/pear
COPY docker/php.ini /usr/local/etc/php/conf.d/zz-olo-orgs.ini
COPY docker/apache.conf /etc/apache2/conf-enabled/zz-olo-orgs.conf
RUN set -eux; \
    sed -ri 's/^Listen 80$/Listen 8080/' /etc/apache2/ports.conf; \
    sed -ri 's/<VirtualHost \*:80>/<VirtualHost *:8080>/' /etc/apache2/sites-available/000-default.conf; \
    rm -rf /opt/drupal

FROM runtime AS build
COPY --from=composer /usr/bin/composer /usr/local/bin/composer
WORKDIR /opt/drupal
COPY composer.json composer.lock ./
RUN --mount=type=cache,id=olo-orgs-composer,target=/root/.composer/cache \
    COMPOSER_ALLOW_SUPERUSER=1 composer install --no-dev --no-interaction --no-progress --optimize-autoloader
COPY modules.yml ./
COPY scripts ./scripts
COPY config ./config
COPY web/modules/custom ./web/modules/custom
COPY web/themes/custom ./web/themes/custom
COPY web/sites/default/settings.php web/sites/default/services.yml ./web/sites/default/
# The module list, composer.json and core.extension.yml must agree (prompt §6.3).
RUN php scripts/build-modules.php --check
RUN chmod -R a-w /opt/drupal

FROM runtime
ARG TOOLGATE_REVISION=unknown
LABEL org.opencontainers.image.title="olo-orgs-marketplace-drupal" \
      org.opencontainers.image.description="OLO Orgs: the ToolGate tool marketplace (Drupal)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.ololabs.toolgate.revision="${TOOLGATE_REVISION}"
COPY --from=build /opt/drupal /opt/drupal
COPY --from=seed /seed /opt/olo/seed
COPY --from=marketplace seed/expected-packages.json /opt/olo/seed/expected-packages.json
ENV PATH=/opt/drupal/vendor/bin:$PATH \
    TOOLGATE_REVISION=${TOOLGATE_REVISION} \
    APACHE_RUN_DIR=/tmp/apache2 \
    APACHE_PID_FILE=/tmp/apache2/apache2.pid \
    APACHE_LOCK_DIR=/tmp/apache2/lock \
    APACHE_LOG_DIR=/tmp/apache2/log \
    HOME=/tmp
WORKDIR /opt/drupal
USER www-data
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD php /opt/drupal/scripts/healthcheck.php
CMD ["apache2-foreground"]
