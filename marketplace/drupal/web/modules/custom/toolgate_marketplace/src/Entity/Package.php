<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Entity;

use Drupal\Core\Entity\Attribute\ContentEntityType;
use Drupal\Core\Entity\ContentEntityBase;
use Drupal\Core\Entity\EntityChangedTrait;
use Drupal\Core\Entity\EntityTypeInterface;
use Drupal\Core\Field\BaseFieldDefinition;
use Drupal\Core\StringTranslation\TranslatableMarkup;
use Drupal\toolgate_marketplace\Access\MarketplaceAccessControlHandler;

/**
 * A package id, owned by one publisher (design §4.2).
 */
#[ContentEntityType(
  id: 'tgm_package',
  label: new TranslatableMarkup('Package'),
  entity_keys: [
    'id' => 'id',
    'uuid' => 'uuid',
    'label' => 'package_id',
  ],
  handlers: [
    'access' => MarketplaceAccessControlHandler::class,
    'storage_schema' => MarketplaceStorageSchema::class,
  ],
  base_table: 'tgm_package',
  admin_permission: 'administer toolgate marketplace',
)]
final class Package extends ContentEntityBase {

  use EntityChangedTrait;

  public static function baseFieldDefinitions(EntityTypeInterface $entity_type): array {
    $fields = parent::baseFieldDefinitions($entity_type);
    $fields['package_id'] = BaseFieldDefinition::create('string')
      ->setLabel(new TranslatableMarkup('Package id'))
      ->setRequired(TRUE)
      ->setSetting('max_length', 127);
    $fields['publisher'] = BaseFieldDefinition::create('entity_reference')
      ->setLabel(new TranslatableMarkup('Publisher'))
      ->setRequired(TRUE)
      ->setSetting('target_type', 'tgm_publisher');
    $fields['created'] = BaseFieldDefinition::create('created')
      ->setLabel(new TranslatableMarkup('Created'));
    $fields['changed'] = BaseFieldDefinition::create('changed')
      ->setLabel(new TranslatableMarkup('Changed'));
    return $fields;
  }

  public function packageId(): string {
    return (string) $this->get('package_id')->value;
  }

  public function publisher(): ?Publisher {
    return $this->get('publisher')->entity;
  }

}
