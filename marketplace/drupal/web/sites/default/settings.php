<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

/**
 * @file
 * OLO Orgs settings: everything comes from the environment (prompt §6.1).
 *
 * The image holds no environment-specific file. Database, Redis, object
 * storage, the hash salt, trusted hosts, the reverse proxy, the helper and
 * the marketplace id are all read here from MARKETPLACE_* variables, and
 * secrets may instead come from files (<NAME>_FILE), as Kubernetes and
 * Compose secrets are mounted.
 */

$env = static function (string $name, ?string $default = NULL): ?string {
  $file = getenv($name . '_FILE');
  if ($file !== FALSE && $file !== '' && is_readable($file)) {
    return rtrim((string) file_get_contents($file), "\r\n");
  }
  $value = getenv($name);
  return ($value === FALSE || $value === '') ? $default : $value;
};
$required = static function (string $name) use ($env): string {
  $value = $env($name);
  if ($value === NULL) {
    throw new \RuntimeException("$name must be set.");
  }
  return $value;
};
$flag = static fn(string $name, bool $default) => filter_var($env($name, $default ? 'true' : 'false'), FILTER_VALIDATE_BOOLEAN);

// Database: external PostgreSQL (MariaDB/MySQL also work). SQLite only for
// the module's own test harness, never in an image deployment.
$driver = $env('MARKETPLACE_DB_DRIVER', 'pgsql');
$databases['default']['default'] = $driver === 'sqlite'
  ? ['driver' => 'sqlite', 'database' => $required('MARKETPLACE_DB_NAME')]
  : [
    'driver' => $driver,
    'host' => $required('MARKETPLACE_DB_HOST'),
    'port' => $env('MARKETPLACE_DB_PORT', $driver === 'pgsql' ? '5432' : '3306'),
    'database' => $required('MARKETPLACE_DB_NAME'),
    'username' => $required('MARKETPLACE_DB_USER'),
    'password' => $required('MARKETPLACE_DB_PASSWORD'),
    'prefix' => '',
  ] + ($driver === 'mysql' ? ['collation' => 'utf8mb4_general_ci', 'isolation_level' => 'READ COMMITTED'] : []);

$settings['hash_salt'] = $required('DRUPAL_HASH_SALT');
$settings['config_sync_directory'] = '../config/sync';
$settings['deployment_identifier'] = $env('TOOLGATE_REVISION', 'dev');
$settings['update_free_access'] = FALSE;
$settings['allow_authorize_operations'] = FALSE;
$settings['file_temp_path'] = '/tmp';
$settings['php_storage']['default']['directory'] = '/tmp/drupal-php';
$settings['php_storage']['twig']['directory'] = '/tmp/drupal-php';
$settings['entity_update_batch_size'] = 50;
$settings['state_cache'] = TRUE;
$settings['container_yamls'][] = $app_root . '/' . $site_path . '/services.yml';
$config['system.logging']['error_level'] = $env('MARKETPLACE_ERROR_LEVEL', 'hide');

// Public origin, trusted hosts and the reverse proxy.
$baseUrl = rtrim($env('MARKETPLACE_BASE_URL', 'http://localhost:8080'), '/');
$hosts = array_filter(array_merge([parse_url($baseUrl, PHP_URL_HOST)], explode(',', (string) $env('MARKETPLACE_TRUSTED_HOSTS', ''))));
$settings['trusted_host_patterns'] = array_values(array_map(static fn(string $host) => '^' . preg_quote(trim($host)) . '$', $hosts));
if ($proxies = $env('MARKETPLACE_REVERSE_PROXY_ADDRESSES')) {
  $settings['reverse_proxy'] = TRUE;
  $settings['reverse_proxy_addresses'] = array_map('trim', explode(',', $proxies));
  $settings['reverse_proxy_trusted_headers'] = \Symfony\Component\HttpFoundation\Request::HEADER_X_FORWARDED_FOR
    | \Symfony\Component\HttpFoundation\Request::HEADER_X_FORWARDED_HOST
    | \Symfony\Component\HttpFoundation\Request::HEADER_X_FORWARDED_PORT
    | \Symfony\Component\HttpFoundation\Request::HEADER_X_FORWARDED_PROTO;
}

// Files: S3-compatible object storage through s3fs (MinIO locally), so no
// replica keeps files on its own disk. MARKETPLACE_S3_ENDPOINT unset means
// local directories, which only the test harness uses.
$s3 = $env('MARKETPLACE_S3_ENDPOINT') !== NULL;
$s3Bucket = $env('MARKETPLACE_S3_BUCKET', 'olo-orgs');
if ($s3) {
  $settings['s3fs.access_key'] = $required('MARKETPLACE_S3_ACCESS_KEY');
  $settings['s3fs.secret_key'] = $required('MARKETPLACE_S3_SECRET_KEY');
  $settings['s3fs.use_s3_for_public'] = TRUE;
  $settings['s3fs.use_s3_for_private'] = TRUE;
  // Core registers private:// only when this is set; s3fs then takes it over.
  $settings['file_private_path'] = '/tmp/unused-private';
  $endpoint = parse_url($required('MARKETPLACE_S3_ENDPOINT'));
  $config['s3fs.settings']['bucket'] = $s3Bucket;
  $config['s3fs.settings']['region'] = $env('MARKETPLACE_S3_REGION', 'us-east-1');
  $config['s3fs.settings']['use_customhost'] = TRUE;
  $config['s3fs.settings']['hostname'] = $endpoint['host'] . (isset($endpoint['port']) ? ':' . $endpoint['port'] : '');
  $config['s3fs.settings']['use_path_style_endpoint'] = TRUE;
  $config['s3fs.settings']['public_folder'] = 's3fs-public';
  $config['s3fs.settings']['private_folder'] = 's3fs-private';
  $config['s3fs.settings']['disable_cert_verify'] = FALSE;
  // Public files (CSS/JS aggregates, image styles) are served through the
  // site's own origin, which proxies /<bucket>/s3fs-public/ to the store,
  // unless MARKETPLACE_S3_PUBLIC_DOMAIN names another host (a CDN, or the
  // bucket's own public endpoint) that serves <domain>/s3fs-public/.
  $config['s3fs.settings']['use_cname'] = TRUE;
  $config['s3fs.settings']['domain'] = $env('MARKETPLACE_S3_PUBLIC_DOMAIN') ?? parse_url($baseUrl, PHP_URL_HOST) . (parse_url($baseUrl, PHP_URL_PORT) ? ':' . parse_url($baseUrl, PHP_URL_PORT) : '') . '/' . $s3Bucket;
  $config['s3fs.settings']['domain_root'] = 'none';
  $config['s3fs.settings']['use_https'] = parse_url($baseUrl, PHP_URL_SCHEME) === 'https';
}
else {
  $settings['file_public_path'] = $env('MARKETPLACE_PUBLIC_PATH', 'sites/default/files');
  $settings['file_private_path'] = $required('MARKETPLACE_PRIVATE_PATH');
}

// Shared cache, locks and flood in Redis, when the redis module is present.
if ($env('MARKETPLACE_REDIS_HOST') && file_exists($app_root . '/modules/contrib/redis/redis.info.yml')) {
  $settings['redis.connection']['interface'] = 'PhpRedis';
  $settings['redis.connection']['host'] = $env('MARKETPLACE_REDIS_HOST');
  $settings['redis.connection']['port'] = (int) $env('MARKETPLACE_REDIS_PORT', '6379');
  if ($password = $env('MARKETPLACE_REDIS_PASSWORD')) {
    $settings['redis.connection']['password'] = $password;
  }
  $settings['cache_prefix'] = 'olo_orgs';
  // Until the module is installed by the first deploy, keep core's bins.
  if (!\Drupal\Core\Installer\InstallerKernel::installationAttempted() && extension_loaded('redis')) {
    $settings['cache']['default'] = 'cache.backend.redis';
    $settings['container_yamls'][] = $app_root . '/modules/contrib/redis/example.services.yml';
  }
}

// Configuration comes only from config/sync; the deploy job turns the lock
// off for its own import.
$settings['config_readonly'] = $flag('MARKETPLACE_CONFIG_READONLY', TRUE);

// Mail: core's Symfony mailer over MARKETPLACE_SMTP_DSN (smtp://user:pass@host:port).
if ($dsn = $env('MARKETPLACE_SMTP_DSN')) {
  $parts = parse_url($dsn);
  $config['system.mail']['interface']['default'] = 'symfony_mailer';
  $config['system.mail']['mailer_dsn'] = [
    'scheme' => $parts['scheme'] ?? 'smtp',
    'host' => $parts['host'] ?? 'localhost',
    'user' => isset($parts['user']) ? urldecode($parts['user']) : NULL,
    'password' => isset($parts['pass']) ? urldecode($parts['pass']) : NULL,
    'port' => isset($parts['port']) ? (int) $parts['port'] : NULL,
    'options' => [],
  ];
}
$config['system.site']['mail'] = $env('MARKETPLACE_MAIL_FROM', 'noreply@localhost');
$config['system.site']['name'] = $env('MARKETPLACE_SITE_NAME', 'OLO Orgs');
$contact = $env('MARKETPLACE_CONTACT_EMAIL', $config['system.site']['mail']);
$config['contact.form.report_abuse']['recipients'] = [$contact];
$config['contact.form.feedback']['recipients'] = [$contact];

// The marketplace itself (module toolgate_marketplace).
$settings['toolgate_marketplace'] = [
  'id' => $required('MARKETPLACE_ID'),
  'base_url' => $baseUrl,
  'helper_url' => $env('MARKETPLACE_HELPER_URL', 'http://helper:8080'),
  'helper_token' => $required('MARKETPLACE_HELPER_TOKEN'),
  'helper_timeout' => (int) $env('MARKETPLACE_VALIDATION_TIMEOUT_SECONDS', '30'),
  'max_package_mb' => (int) $env('MARKETPLACE_MAX_PACKAGE_MB', '50'),
  'reserved_prefixes' => $env('MARKETPLACE_RESERVED_PREFIXES', 'olo,ololabs,toolgate,examples'),
  'system_publishers' => $env('MARKETPLACE_SYSTEM_PUBLISHERS', 'examples'),
  'file_refs' => $s3 ? 's3' : 'file',
  's3_bucket' => $s3Bucket,
  's3_private_folder' => 's3fs-private',
  'helper_file_root' => $env('MARKETPLACE_HELPER_FILE_ROOT', $settings['file_private_path']),
  'seed_strict' => $flag('MARKETPLACE_SEED_STRICT', FALSE),
];
