<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Core\Site\Settings;

/**
 * Environment-owned settings, set in settings.php from MARKETPLACE_* variables.
 *
 * None of these live in configuration, so a config import can't change where
 * Drupal sends packages or what it calls the marketplace.
 */
final class MarketplaceSettings {

  private array $values;

  public function __construct(?array $values = NULL) {
    $this->values = ($values ?? Settings::get('toolgate_marketplace', [])) + [
      'id' => 'olo-labs-marketplace',
      'base_url' => '',
      'helper_url' => 'http://helper:8080',
      'helper_token' => '',
      'helper_timeout' => 30,
      'max_package_mb' => 50,
      'reserved_prefixes' => 'olo,ololabs,toolgate,examples',
      'system_publishers' => 'examples',
      'file_refs' => 'file',
      's3_bucket' => '',
      's3_private_folder' => 's3fs-private',
      'helper_file_root' => '',
      'seed_strict' => FALSE,
    ];
  }

  public function get(string $key): mixed {
    return $this->values[$key] ?? NULL;
  }

  public function marketplaceId(): string {
    return (string) $this->values['id'];
  }

  /**
   * The public origin without a trailing slash; empty means "this request".
   */
  public function baseUrl(): string {
    return rtrim((string) $this->values['base_url'], '/');
  }

  public function maxPackageBytes(): int {
    return (int) $this->values['max_package_mb'] * 1024 * 1024;
  }

  /**
   * @return list<string>
   */
  public function reservedPrefixes(): array {
    return self::csv($this->values['reserved_prefixes']);
  }

  /**
   * @return list<string>
   */
  public function systemPublishers(): array {
    return self::csv($this->values['system_publishers']);
  }

  /**
   * @return list<string>
   */
  private static function csv(mixed $value): array {
    $items = is_array($value) ? $value : explode(',', (string) $value);
    return array_values(array_filter(array_map(static fn($item) => strtolower(trim((string) $item)), $items)));
  }

}
