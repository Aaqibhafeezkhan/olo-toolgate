<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

/**
 * @file
 * Keeps modules.yml, composer.json and config/sync/core.extension.yml in step
 * (implementation prompt §6.3).
 *
 *   php scripts/build-modules.php --sync    Requires every listed package with
 *                                           Composer (pinned in composer.lock)
 *                                           and enables every listed name in
 *                                           core.extension.yml.
 *   php scripts/build-modules.php --check   Fails when they disagree: a listed
 *                                           package missing from composer.json
 *                                           or not installed, a listed name not
 *                                           enabled, or an enabled module that
 *                                           is neither listed, core, custom nor
 *                                           needed by one of those.
 *
 * Run from marketplace/drupal after `composer install`. The image build runs
 * --check, so a module can't be added or dropped without updating the list.
 */

use Symfony\Component\Yaml\Yaml;

$root = dirname(__DIR__);
require $root . '/vendor/autoload.php';

$mode = $argv[1] ?? '--check';
if (!in_array($mode, ['--check', '--sync'], TRUE)) {
  fwrite(STDERR, "usage: php scripts/build-modules.php --check|--sync\n");
  exit(2);
}

$list = Yaml::parseFile($root . '/modules.yml');
$extensionFile = $root . '/config/sync/core.extension.yml';
$extension = Yaml::parseFile($extensionFile);
$composer = json_decode(file_get_contents($root . '/composer.json'), TRUE, 64, JSON_THROW_ON_ERROR);

// Modules every install has: the profile's own, the database driver and what
// core itself requires.
const BASE = ['system', 'user', 'field', 'filter', 'text', 'file', 'node', 'block', 'dblog', 'path_alias', 'pgsql', 'minimal'];

$listedModules = [];
$listedThemes = [];
foreach ($list['modules'] ?? [] as $entry) {
  foreach ($entry['enable'] as $name) {
    if (($entry['type'] ?? 'module') === 'theme') {
      $listedThemes[$name] = $entry['package'];
    }
    else {
      $listedModules[$name] = $entry['package'];
    }
  }
}
$coreList = $list['core'] ?? [];

if ($mode === '--sync') {
  foreach ($list['modules'] ?? [] as $entry) {
    $spec = $entry['package'] . ':' . $entry['version'];
    passthru('composer require --no-interaction --no-update ' . escapeshellarg($spec), $status);
    if ($status !== 0) {
      exit($status);
    }
  }
  passthru('composer update --no-interaction --with-all-dependencies ' . implode(' ', array_map('escapeshellarg', array_column($list['modules'] ?? [], 'package'))), $status);
  if ($status !== 0) {
    exit($status);
  }
  foreach ([...array_keys($listedModules), ...$coreList] as $name) {
    $extension['module'][$name] ??= 0;
  }
  foreach (array_keys($listedThemes) as $name) {
    $extension['theme'][$name] ??= 0;
  }
  // Keep core's order: by weight, then name; the profile stays last.
  uksort($extension['module'], static fn($a, $b) => [$extension['module'][$a], $a] <=> [$extension['module'][$b], $b]);
  file_put_contents($extensionFile, Yaml::dump($extension, 4, 2));
  echo "modules.yml synced into composer.json, composer.lock and core.extension.yml\n";
  exit(0);
}

// --check
$problems = [];
foreach ($list['modules'] ?? [] as $entry) {
  $required = $composer['require'][$entry['package']] ?? NULL;
  if ($required !== $entry['version']) {
    $problems[] = sprintf('%s: modules.yml asks for %s, composer.json has %s', $entry['package'], $entry['version'], $required ?? 'nothing');
  }
}

// Every installed extension's info.yml, for dependencies and for "is it core".
$info = [];
foreach (['web/core/modules', 'web/core/profiles', 'web/core/themes', 'web/modules', 'web/themes', 'web/profiles'] as $dir) {
  if (!is_dir("$root/$dir")) {
    continue;
  }
  $files = new RecursiveIteratorIterator(new RecursiveDirectoryIterator("$root/$dir", FilesystemIterator::SKIP_DOTS));
  foreach ($files as $file) {
    if (preg_match('/^([a-z0-9_]+)\.info\.yml$/', $file->getFilename(), $m) && !str_contains($file->getPathname(), '/tests/')) {
      $info[$m[1]] = ['path' => substr($file->getPath(), strlen($root) + 1)] + (Yaml::parseFile($file->getPathname()) ?? []);
    }
  }
}
$custom = array_keys(array_filter($info, static fn($i) => str_contains($i['path'], '/custom/')));
foreach ([...array_keys($listedModules), ...array_keys($listedThemes)] as $name) {
  if (!isset($info[$name])) {
    $problems[] = "$name is listed in modules.yml but its package is not installed (run composer install)";
  }
}

// The closure of what the list, core list, custom code and base need.
$wanted = array_merge(array_keys($listedModules), $coreList, $custom, BASE);
$needed = [];
while ($name = array_pop($wanted)) {
  if (isset($needed[$name])) {
    continue;
  }
  $needed[$name] = TRUE;
  foreach ($info[$name]['dependencies'] ?? [] as $dependency) {
    $wanted[] = preg_replace('/^[a-z0-9_]+:|\s.*$/', '', $dependency);
  }
}
foreach (array_keys($extension['module'] ?? []) as $name) {
  if (!isset($needed[$name])) {
    $problems[] = "$name is enabled in core.extension.yml but is not in modules.yml, the core list, custom code or their dependencies";
  }
}
foreach ([...array_keys($listedModules), ...$coreList] as $name) {
  if (!isset($extension['module'][$name])) {
    $problems[] = "$name is listed but not enabled in core.extension.yml (run --sync)";
  }
}
foreach (array_keys($listedThemes) as $name) {
  if (!isset($extension['theme'][$name])) {
    $problems[] = "theme $name is listed but not enabled in core.extension.yml (run --sync)";
  }
}

if ($problems) {
  fwrite(STDERR, "modules.yml check failed:\n  - " . implode("\n  - ", $problems) . "\n");
  exit(1);
}
echo "modules.yml, composer.json and core.extension.yml agree\n";
