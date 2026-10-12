{{/* Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 */}}
{{/*
  The environment of every Drupal container, rendered inline rather than from a ConfigMap so the pre-upgrade deploy
  Job sees the new release's settings before any resource of that release exists.
*/}}
{{- define "olo-orgs.drupalEnvData" -}}
{{- $m := .Values.marketplace -}}
MARKETPLACE_ID: {{ $m.id | quote }}
MARKETPLACE_BASE_URL: {{ $m.baseUrl | quote }}
MARKETPLACE_SITE_NAME: {{ $m.siteName | quote }}
MARKETPLACE_TRUSTED_HOSTS: {{ join "," $m.trustedHosts | quote }}
MARKETPLACE_REVERSE_PROXY_ADDRESSES: {{ join "," $m.reverseProxyAddresses | quote }}
MARKETPLACE_MAX_PACKAGE_MB: {{ $m.maxPackageMb | quote }}
MARKETPLACE_VALIDATION_TIMEOUT_SECONDS: {{ $m.validationTimeoutSeconds | quote }}
MARKETPLACE_RESERVED_PREFIXES: {{ $m.reservedPrefixes | quote }}
MARKETPLACE_SYSTEM_PUBLISHERS: {{ $m.systemPublishers | quote }}
MARKETPLACE_SEED_EXAMPLES: {{ $m.seedExamples | quote }}
MARKETPLACE_SEED_STRICT: {{ $m.seedStrict | quote }}
MARKETPLACE_MAIL_FROM: {{ $m.mailFrom | quote }}
MARKETPLACE_CONTACT_EMAIL: {{ $m.contactEmail | quote }}
MARKETPLACE_SMTP_DSN: {{ $m.smtpDsn | quote }}
MARKETPLACE_ADMIN_USER: {{ $m.adminUser | quote }}
MARKETPLACE_ADMIN_MAIL: {{ $m.adminMail | quote }}
MARKETPLACE_DB_DRIVER: {{ $m.database.driver | quote }}
MARKETPLACE_DB_HOST: {{ $m.database.host | quote }}
MARKETPLACE_DB_PORT: {{ $m.database.port | quote }}
MARKETPLACE_DB_NAME: {{ $m.database.name | quote }}
MARKETPLACE_DB_USER: {{ $m.database.user | quote }}
MARKETPLACE_REDIS_HOST: {{ $m.redis.host | quote }}
MARKETPLACE_REDIS_PORT: {{ $m.redis.port | quote }}
MARKETPLACE_S3_ENDPOINT: {{ $m.s3.endpoint | quote }}
MARKETPLACE_S3_REGION: {{ $m.s3.region | quote }}
MARKETPLACE_S3_BUCKET: {{ $m.s3.bucket | quote }}
MARKETPLACE_S3_PUBLIC_DOMAIN: {{ $m.s3.publicDomain | quote }}
MARKETPLACE_HELPER_URL: {{ printf "http://%s-helper:8080" (include "olo-orgs.fullname" .) | quote }}
MARKETPLACE_HELPER_FILE_ROOT: ''
TOOLGATE_REVISION: {{ default .Chart.AppVersion .Values.drupal.image.tag | quote }}
# Secrets, as files from the {{ .Values.secrets.drupal }} Secret.
DRUPAL_HASH_SALT_FILE: /run/secrets/drupal/hash_salt
MARKETPLACE_HELPER_TOKEN_FILE: /run/secrets/drupal/helper_token
MARKETPLACE_DB_PASSWORD_FILE: /run/secrets/drupal/db_password
MARKETPLACE_S3_ACCESS_KEY_FILE: /run/secrets/drupal/s3_access_key
MARKETPLACE_S3_SECRET_KEY_FILE: /run/secrets/drupal/s3_secret_key
MARKETPLACE_ADMIN_PASSWORD_FILE: /run/secrets/drupal/admin_password
MARKETPLACE_REDIS_PASSWORD_FILE: /run/secrets/drupal/redis_password
{{- range $name, $value := $m.extraEnv }}
{{ $name }}: {{ $value | quote }}
{{- end }}
{{- end -}}
