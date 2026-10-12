<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Core\Cache\CacheBackendInterface;
use Drupal\Core\State\StateInterface;

/**
 * The public key set, as published by the helper (docs/api.md §1 KeySet).
 *
 * Drupal never holds the private key; it republishes the helper's public
 * keys. The last good copy is kept in state, so the keys file stays served
 * while the helper restarts.
 */
final class KeySet {

  private const CACHE_ID = 'toolgate_marketplace:keys';
  private const STATE_KEY = 'toolgate_marketplace.keys';

  public function __construct(
    private readonly HelperClient $helper,
    private readonly CacheBackendInterface $cache,
    private readonly StateInterface $state,
  ) {}

  public function get(): ?array {
    if ($cached = $this->cache->get(self::CACHE_ID)) {
      return $cached->data;
    }
    try {
      $keys = $this->helper->keys();
      unset($keys['toolgateRevision']);
      if (!is_string($keys['marketplace'] ?? NULL) || !is_array($keys['keys'] ?? NULL)) {
        throw new HelperException('UNAVAILABLE', 'The helper returned no key set.', TRUE);
      }
      $this->state->set(self::STATE_KEY, $keys);
      $this->cache->set(self::CACHE_ID, $keys, time() + 300);
      return $keys;
    }
    catch (HelperException) {
      return $this->state->get(self::STATE_KEY);
    }
  }

  /**
   * A published key by id, current or retired.
   */
  public function find(string $keyId): ?array {
    foreach ($this->get()['keys'] ?? [] as $key) {
      if (($key['keyId'] ?? NULL) === $keyId) {
        return $key;
      }
    }
    return NULL;
  }

  /**
   * The current key, for the About signing page and the tool pages.
   */
  public function current(): ?array {
    foreach ($this->get()['keys'] ?? [] as $key) {
      if (($key['status'] ?? '') === 'current') {
        return $key;
      }
    }
    return NULL;
  }

}
