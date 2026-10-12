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
 * One uploaded package version, from upload to delisting (design §4.2, §4.3).
 *
 * The technical fields come only from the helper's inspect result and the
 * signed release record; the listing fields are publisher-entered. The
 * package id and version are claimed (and so unique) only once the version is
 * validated and signed: a rejected upload never blocks the version.
 */
#[ContentEntityType(
  id: 'tgm_version',
  label: new TranslatableMarkup('Package version'),
  entity_keys: [
    'id' => 'id',
    'uuid' => 'uuid',
    'owner' => 'uid',
  ],
  handlers: [
    'access' => MarketplaceAccessControlHandler::class,
    'storage_schema' => MarketplaceStorageSchema::class,
  ],
  base_table: 'tgm_version',
  admin_permission: 'administer toolgate marketplace',
)]
final class Version extends ContentEntityBase implements EntityOwnerInterface {

  use EntityChangedTrait;
  use EntityOwnerTrait;

  public const UPLOADED = 'UPLOADED';
  public const VALIDATING = 'VALIDATING';
  public const VALIDATED = 'VALIDATED';
  public const AVAILABLE = 'AVAILABLE';
  public const DELISTED = 'DELISTED';
  public const REJECTED = 'REJECTED';

  /**
   * Fields the publisher may edit; everything else comes from the package.
   */
  public const LISTING_FIELDS = ['title', 'summary', 'description', 'category', 'tags', 'repository_url', 'documentation_url', 'license', 'screenshots'];

  /**
   * Fields that never change once the version is validated.
   */
  private const IMMUTABLE_FIELDS = ['package_id', 'version', 'file_uri', 'package_digest', 'descriptor_set_digest', 'archive_sha256', 'release', 'inspect', 'publisher', 'package'];

  public static function baseFieldDefinitions(EntityTypeInterface $entity_type): array {
    $fields = parent::baseFieldDefinitions($entity_type);
    $fields += static::ownerBaseFieldDefinitions($entity_type);
    $string = static fn(string $label, int $length = 255) => BaseFieldDefinition::create('string')
      ->setLabel(new TranslatableMarkup($label))
      ->setSetting('max_length', $length);
    $long = static fn(string $label) => BaseFieldDefinition::create('string_long')
      ->setLabel(new TranslatableMarkup($label));
    $time = static fn(string $label) => BaseFieldDefinition::create('timestamp')
      ->setLabel(new TranslatableMarkup($label));

    $fields['publisher'] = BaseFieldDefinition::create('entity_reference')
      ->setLabel(new TranslatableMarkup('Publisher'))
      ->setRequired(TRUE)
      ->setSetting('target_type', 'tgm_publisher');
    $fields['package'] = BaseFieldDefinition::create('entity_reference')
      ->setLabel(new TranslatableMarkup('Package'))
      ->setSetting('target_type', 'tgm_package');
    $fields['package_id'] = $string('Package id', 127);
    $fields['version'] = $string('Version', 128);
    $fields['status'] = $string('Status', 16)->setRequired(TRUE)->setDefaultValue(self::UPLOADED);
    $fields['file_uri'] = $string('Stored package', 512);
    $fields['size_bytes'] = BaseFieldDefinition::create('integer')
      ->setLabel(new TranslatableMarkup('Size in bytes'))
      ->setSetting('size', 'big');
    $fields['package_digest'] = $string('Package digest', 71);
    $fields['descriptor_set_digest'] = $string('Descriptor set digest', 71);
    $fields['archive_sha256'] = $string('Archive SHA-256', 64);
    $fields['runtime'] = $string('Runtime', 32);
    $fields['api_change'] = $string('API change', 16);
    $fields['inspect'] = $long('Inspect result (JSON)');
    $fields['release'] = $long('Signed release record (JSON)');
    $fields['errors'] = $long('Validation errors (JSON)');

    $fields['title'] = $string('Title', 120);
    $fields['summary'] = $string('Summary', 280);
    $fields['description'] = $long('Description (Markdown)');
    $fields['category'] = $string('Category', 64);
    $fields['tags'] = $string('Tags', 512);
    $fields['repository_url'] = $string('Repository URL');
    $fields['documentation_url'] = $string('Documentation URL');
    $fields['license'] = $string('License', 64);
    $fields['screenshots'] = BaseFieldDefinition::create('image')
      ->setLabel(new TranslatableMarkup('Screenshots'))
      ->setCardinality(4)
      ->setSettings([
        'file_extensions' => 'png jpg jpeg',
        'file_directory' => 'toolgate/screenshots',
        'max_filesize' => '1 MB',
        'max_resolution' => '2560x1600',
        'uri_scheme' => 'private',
        'alt_field' => TRUE,
        'alt_field_required' => TRUE,
      ]);

    $fields['created'] = BaseFieldDefinition::create('created')->setLabel(new TranslatableMarkup('Uploaded'));
    $fields['changed'] = BaseFieldDefinition::create('changed')->setLabel(new TranslatableMarkup('Changed'));
    $fields['validated'] = $time('Validated');
    $fields['published'] = $time('Published');
    $fields['delisted'] = $time('Delisted');
    $fields['delist_reason'] = $string('Delist reason', 500);
    return $fields;
  }

  public function status(): string {
    return (string) $this->get('status')->value;
  }

  public function packageId(): ?string {
    return $this->get('package_id')->value;
  }

  public function version(): ?string {
    return $this->get('version')->value;
  }

  public function publisher(): ?Publisher {
    return $this->get('publisher')->entity;
  }

  /**
   * @return array<string, mixed>
   */
  public function json(string $field): array {
    $value = $this->get($field)->value;
    return $value ? json_decode($value, TRUE, 64, JSON_THROW_ON_ERROR) : [];
  }

  /**
   * @return list<string>
   */
  public function tags(): array {
    $tags = array_map('trim', explode(',', (string) $this->get('tags')->value));
    return array_values(array_filter($tags, static fn(string $tag) => $tag !== ''));
  }

  /**
   * Published bytes and version identity can't change (design §4.3).
   */
  public function preSave(EntityStorageInterface $storage): void {
    parent::preSave($storage);
    $original = $this->getOriginal();
    if ($this->isNew() || !$original instanceof Version) {
      return;
    }
    if (in_array($original->status(), [self::VALIDATED, self::AVAILABLE, self::DELISTED], TRUE)) {
      foreach (self::IMMUTABLE_FIELDS as $field) {
        if ($this->get($field)->getValue() != $original->get($field)->getValue()) {
          throw new \LogicException("$field is immutable once a version is validated.");
        }
      }
    }
  }

}
