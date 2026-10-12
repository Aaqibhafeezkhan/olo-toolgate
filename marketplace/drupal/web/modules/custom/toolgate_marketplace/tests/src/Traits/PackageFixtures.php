<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\Tests\toolgate_marketplace\Traits;

/**
 * Real .tgpkg files for tests, packed with the unchanged `toolgate pack`.
 *
 * The template is the helper's own fixture manifest (examples.notify renamed
 * to acme.notify). Tests run against the real helper too: nothing about
 * validation or signing is mocked. scripts/run-tests.sh provides:
 * - MARKETPLACE_TEST_TOOLGATE_CLI: the toolgate CLI;
 * - MARKETPLACE_TEST_HELPER_URL and MARKETPLACE_TEST_HELPER_TOKEN: a helper
 *   whose MARKETPLACE_HELPER_FILE_ROOT is MARKETPLACE_TEST_FILE_ROOT, a
 *   directory that contains the test sites' private directories.
 */
trait PackageFixtures {

  protected static function env(string $name): string {
    $value = getenv($name);
    if ($value === FALSE || $value === '') {
      throw new \RuntimeException("$name is not set; run the tests with marketplace/drupal/scripts/run-tests.sh.");
    }
    return $value;
  }

  protected static function marketplaceDir(): string {
    return getenv('MARKETPLACE_DIR') ?: dirname(__DIR__, 8);
  }

  protected static function template(): array {
    $path = self::marketplaceDir() . '/helper/src/test/resources/fixture-manifest.json';
    return json_decode(file_get_contents($path), TRUE, 512, JSON_THROW_ON_ERROR);
  }

  /**
   * Packs the template, changed by $change, and returns the file path.
   */
  protected static function pack(?callable $change = NULL, string $id = 'acme.notify', string $version = '0.1.0'): string {
    $manifest = self::template();
    $manifest['id'] = $id;
    $manifest['version'] = $version;
    foreach ($manifest['tools'] as &$tool) {
      $toolId = $tool['descriptor']['toolId'];
      $tool['descriptor']['toolId'] = $id . substr($toolId, strpos($toolId, '/'));
    }
    unset($tool);
    if ($change) {
      $change($manifest);
    }
    $dir = sys_get_temp_dir() . '/tgm-fixture-' . bin2hex(random_bytes(6));
    mkdir($dir . '/artifacts', 0777, TRUE);
    file_put_contents($dir . '/toolgate-package.json', json_encode($manifest, JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
    file_put_contents($dir . '/artifacts/tool.jar', 'not really a jar');
    $out = $dir . '/' . str_replace('.', '-', $id) . '-' . $version . '.tgpkg';
    $command = [self::env('MARKETPLACE_TEST_TOOLGATE_CLI'), 'pack', $dir, '-o', $out];
    $process = proc_open($command, [1 => ['pipe', 'w'], 2 => ['redirect', 1]], $pipes);
    $output = stream_get_contents($pipes[1]);
    fclose($pipes[1]);
    if (proc_close($process) !== 0) {
      throw new \RuntimeException("toolgate pack failed: $output");
    }
    return $out;
  }

  /**
   * A file with a .tgpkg name that isn't a package.
   */
  protected static function garbage(): string {
    $path = sys_get_temp_dir() . '/tgm-garbage-' . bin2hex(random_bytes(6)) . '.tgpkg';
    file_put_contents($path, 'PK this is not a zip archive');
    return $path;
  }

  /**
   * RFC 8785 canonical JSON for the release record (all members are strings).
   */
  protected static function canonical(array $value): string {
    ksort($value, SORT_STRING);
    return json_encode($value, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
  }

}
