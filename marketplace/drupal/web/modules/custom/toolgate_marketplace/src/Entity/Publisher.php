<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Entity;

use Drupal\Core\Entity\Attribute\ContentEntityType;
use Drupal\Core\Entity\ContentEntityBase;
use Drupal\Core\Entity\EntityChangedTrait;
use Drupal\Core\Entity\EntityStorageInterface;
use Drupal\Core\Entity\EntityTypeInterface;
use Drupal\Core\Field\BaseFieldDefinition;
use Drupal\Core\StringTranslation\TranslatableMarkup;
use Drupal\toolgate_marketplace\Access\MarketplaceAccessControlHandler;
use Drupal\user\EntityOwnerInterface;
use Drupal\user\EntityOwnerTrait;

/**
 * A public publisher profile: one per account, with an immutable slug.
 *
 * The slug is the package-id namespace and is signed into every release, so
 * it is set once at creation and never changes (design §4.1). Everything else
 * is unsigned display data the publisher can edit at any time.
 */
#[ContentEntityType(
  id: 'tgm_publisher',
  label: new TranslatableMarkup('Publisher'),
  label_collection: new TranslatableMarkup('Publishers'),
  entity_keys: [
    'id' => 'id',
    'uuid' => 'uuid',
    'label' => 'display_name',
    'owner' => 'uid',
  ],
  handlers: [
    'access' => MarketplaceAccessControlHandler::class,
    'storage_schema' => MarketplaceStorageSchema::class,
  ],
  base_table: 'tgm_publisher',
  admin_permission: 'administer toolgate marketplace',
)]
final class Publisher extends ContentEntityBase implements EntityOwnerInterface {

  use EntityChangedTrait;
  use EntityOwnerTrait;

  public const SLUG_PATTERN = '/^[a-z][a-z0-9-]{1,30}$/';

  public static function baseFieldDefinitions(EntityTypeInterface $entity_type): array {
    $fields = parent::baseFieldDefinitions($entity_type);
    $fields += static::ownerBaseFieldDefinitions($entity_type);
    $fields['uid']->setRequired(TRUE);

    $fields['slug'] = BaseFieldDefinition::create('string')
      ->setLabel(new TranslatableMarkup('Slug'))
      ->setDescription(new TranslatableMarkup('Your package-id prefix and profile URL. It can never be changed.'))
      ->setRequired(TRUE)
      ->setSetting('max_length', 31)
      ->addPropertyConstraints('value', ['Regex' => ['pattern' => self::SLUG_PATTERN]]);
    $fields['display_name'] = BaseFieldDefinition::create('string')
      ->setLabel(new TranslatableMarkup('Display name'))
      ->setRequired(TRUE)
      ->setSetting('max_length', 80);
    $fields['avatar'] = BaseFieldDefinition::create('image')
      ->setLabel(new TranslatableMarkup('Avatar'))
      ->setSettings([
        'file_extensions' => 'png jpg jpeg',
        'file_directory' => 'toolgate/avatars',
        'max_filesize' => '1 MB',
        'max_resolution' => '1024x1024',
        'min_resolution' => '64x64',
        'uri_scheme' => 'private',
        'alt_field' => FALSE,
      ]);
    $fields['bio'] = BaseFieldDefinition::create('string_long')
      ->setLabel(new TranslatableMarkup('Bio'));
    $fields['organization'] = BaseFieldDefinition::create('string')
      ->setLabel(new TranslatableMarkup('Organization'))
      ->setSetting('max_length', 120);
    foreach (['website_url' => 'Website', 'github_url' => 'GitHub', 'linkedin_url' => 'LinkedIn'] as $name => $label) {
      $fields[$name] = BaseFieldDefinition::create('string')
        ->setLabel(new TranslatableMarkup($label))
        ->setSetting('max_length', 255);
    }
    $fields['country'] = BaseFieldDefinition::create('string')
      ->setLabel(new TranslatableMarkup('Country'))
      ->setSetting('max_length', 2);
    $fields['public_contact_email'] = BaseFieldDefinition::create('email')
      ->setLabel(new TranslatableMarkup('Public contact email'));
    $fields['created'] = BaseFieldDefinition::create('created')
      ->setLabel(new TranslatableMarkup('Created'));
    $fields['changed'] = BaseFieldDefinition::create('changed')
      ->setLabel(new TranslatableMarkup('Changed'));
    return $fields;
  }

  public function slug(): string {
    return (string) $this->get('slug')->value;
  }

  public function displayName(): string {
    return (string) $this->get('display_name')->value;
  }

  /**
   * Refuses any change to the slug after creation.
   */
  public function preSave(EntityStorageInterface $storage): void {
    parent::preSave($storage);
    if (!$this->isNew() && $this->getOriginal() instanceof Publisher && $this->getOriginal()->slug() !== $this->slug()) {
      throw new \LogicException('A publisher slug is immutable.');
    }
  }

}
