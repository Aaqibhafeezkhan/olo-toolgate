<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Core\Cache\CacheBackendInterface;
use Drupal\Core\Database\Connection;
use Drupal\Core\State\StateInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;

/**
 * Readiness of one replica: database, shared cache and object storage.
 */
final class Health {

  public function __construct(
    private readonly Connection $database,
    #[Autowire(service: 'cache.default')]
    private readonly CacheBackendInterface $cache,
    private readonly StateInterface $state,
  ) {}

  /**
   * @return array{status: string, checks: array<string, string>, seed: string, missing: list<array{id: string, reason: string}>}
   */
  public function check(): array {
    $checks = [
      'database' => $this->probe(fn() => (int) $this->database->query('SELECT 1')->fetchField() === 1),
      'cache' => $this->probe(function () {
        $value = bin2hex(random_bytes(8));
        $this->cache->set('toolgate_marketplace:health', $value, time() + 60);
        return ($this->cache->get('toolgate_marketplace:health')->data ?? NULL) === $value;
      }),
      'storage' => $this->probe(function () {
        $uri = 'private://toolgate/health-' . gethostname() . '.txt';
        $value = bin2hex(random_bytes(8));
        @mkdir('private://toolgate', 0775, TRUE);
        $ok = @file_put_contents($uri, $value) !== FALSE && @file_get_contents($uri) === $value;
        @unlink($uri);
        return $ok;
      }),
    ];
    $seed = $this->state->get(SeedRunner::STATUS_KEY) ?? ['seed' => 'unknown', 'missing' => []];
    return [
      'status' => in_array('fail', $checks, TRUE) ? 'fail' : 'ok',
      'checks' => $checks,
      'seed' => $seed['seed'],
      'missing' => $seed['missing'],
    ];
  }

  private function probe(callable $check): string {
    try {
      return $check() ? 'ok' : 'fail';
    }
    catch (\Throwable) {
      return 'fail';
    }
  }

}
