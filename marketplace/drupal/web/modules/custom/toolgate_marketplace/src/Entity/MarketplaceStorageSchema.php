<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Entity;

use Drupal\Core\Entity\ContentEntityTypeInterface;
use Drupal\Core\Entity\Sql\SqlContentEntityStorageSchema;

/**
 * Adds the database unique keys the marketplace relies on.
 *
 * Uniqueness is enforced by the database, not only by a form check, so two
 * replicas racing to claim the same slug or publish the same version can't
 * both succeed (design §4.2).
 */
final class MarketplaceStorageSchema extends SqlContentEntityStorageSchema {

  private const UNIQUE = [
    'tgm_publisher' => [
      'tgm_publisher__slug' => ['slug'],
      'tgm_publisher__uid' => ['uid'],
    ],
    'tgm_package' => [
      'tgm_package__package_id' => ['package_id'],
    ],
    'tgm_version' => [
      'tgm_version__package_version' => ['package_id', 'version'],
    ],
  ];

  /**
   * {@inheritdoc}
   */
  protected function getEntitySchema(ContentEntityTypeInterface $entity_type, $reset = FALSE) {
    $schema = parent::getEntitySchema($entity_type, $reset);
    $table = $this->storage->getBaseTable();
    foreach (self::UNIQUE[$entity_type->id()] ?? [] as $name => $columns) {
      $schema[$table]['unique keys'][$name] = $columns;
    }
    if ($entity_type->id() === 'tgm_version') {
      $schema[$table]['indexes']['tgm_version__status'] = ['status', 'package_id'];
    }
    return $schema;
  }

}
