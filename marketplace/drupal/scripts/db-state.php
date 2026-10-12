<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

/**
 * @file
 * Prints "installed" or "empty" for the configured database, without
 * bootstrapping Drupal and without a database client binary. Exits 1 when
 * the database can't be reached. Used by deploy.sh.
 */

$autoloader = require __DIR__ . '/../vendor/autoload.php';
$app_root = dirname(__DIR__) . '/web';
$site_path = 'sites/default';
$settings = $databases = $config = [];
require $app_root . '/' . $site_path . '/settings.php';
foreach (['mysql', 'pgsql', 'sqlite'] as $driver) {
  $autoloader->addPsr4("Drupal\\$driver\\", "$app_root/core/modules/$driver/src");
}
\Drupal\Core\Database\Database::setMultipleConnectionInfo($databases);
try {
  $connection = \Drupal\Core\Database\Database::getConnection();
  $connection->query('SELECT 1');
  echo $connection->schema()->tableExists('key_value') ? "installed\n" : "empty\n";
}
catch (\Throwable $e) {
  fwrite(STDERR, 'db-state: ' . $e->getMessage() . "\n");
  exit(1);
}
