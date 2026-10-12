<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Drush\Commands;

use Drupal\toolgate_marketplace\HelperException;
use Drupal\toolgate_marketplace\MarketplaceSettings;
use Drupal\toolgate_marketplace\SeedRunner;
use Drush\Attributes as CLI;
use Drush\Commands\AutowireTrait;
use Drush\Commands\DrushCommands;

/**
 * Seeding for the deploy job (prompt §3.2 steps 3 to 6).
 */
final class MarketplaceCommands extends DrushCommands {

  use AutowireTrait;

  public function __construct(
    private readonly SeedRunner $seed,
    private readonly MarketplaceSettings $settings,
  ) {
    parent::__construct();
  }

  /**
   * Publishes the example packages baked into the image.
   *
   * Exits non-zero when the helper is unreachable, and on a degraded seed
   * when --strict or MARKETPLACE_SEED_STRICT=true.
   */
  #[CLI\Command(name: 'toolgate-marketplace:seed')]
  #[CLI\Argument(name: 'dir', description: 'Seed directory with seed-manifest.json and packages/.')]
  #[CLI\Option(name: 'expected', description: 'expected-packages.json; defaults to <dir>/expected-packages.json.')]
  #[CLI\Option(name: 'strict', description: 'Fail when any expected package is missing, knownMissing included.')]
  #[CLI\Usage(name: 'drush toolgate-marketplace:seed /opt/olo/seed', description: 'Seed the examples.')]
  public function seed(string $dir = '/opt/olo/seed', array $options = ['expected' => NULL, 'strict' => FALSE]): int {
    try {
      $status = $this->seed->run($dir, $options['expected']);
    }
    catch (HelperException $e) {
      $this->logger()->error('Seed stopped: the helper is unavailable ({code}: {message}).', ['code' => $e->errorCode, 'message' => $e->getMessage()]);
      return self::EXIT_FAILURE;
    }
    $this->output()->writeln(json_encode($status, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
    foreach ($status['missing'] as $missing) {
      $this->logger()->warning('Missing {id}: {reason}', $missing);
    }
    $strict = $options['strict'] || $this->settings->get('seed_strict');
    if ($status['seed'] !== 'complete' && $strict) {
      $this->logger()->error('Seed is degraded and strict mode is on.');
      return self::EXIT_FAILURE;
    }
    $this->logger()->success('Seed {seed}: {published} published, {skipped} unchanged.', ['seed' => $status['seed'], 'published' => count($status['published']), 'skipped' => count($status['skipped'])]);
    return self::EXIT_SUCCESS;
  }

  /**
   * Prints the last seed status.
   */
  #[CLI\Command(name: 'toolgate-marketplace:seed-status')]
  public function status(): int {
    $this->output()->writeln(json_encode($this->seed->status() ?? ['seed' => 'unknown', 'missing' => []], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
    return self::EXIT_SUCCESS;
  }

}
