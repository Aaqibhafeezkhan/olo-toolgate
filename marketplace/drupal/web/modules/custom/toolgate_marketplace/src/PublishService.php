<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Component\Uuid\UuidInterface;
use Drupal\Component\Datetime\TimeInterface;
use Drupal\Core\Database\Connection;
use Drupal\Core\Database\IntegrityConstraintViolationException;
use Drupal\Core\Entity\EntityStorageException;
use Drupal\Core\Entity\EntityTypeManagerInterface;
use Drupal\Core\File\FileExists;
use Drupal\Core\File\FileSystemInterface;
use Drupal\Core\Session\AccountInterface;
use Drupal\toolgate_marketplace\Entity\Package;
use Drupal\toolgate_marketplace\Entity\Publisher;
use Drupal\toolgate_marketplace\Entity\Version;
use Psr\Log\LoggerInterface;

/**
 * The one publishing path, used by the upload form and the seed alike.
 *
 * Upload → /inspect → ownership and uniqueness from the logged-in account →
 * /sign → VALIDATED private draft → listing saved → AVAILABLE (design §5.2).
 */
final class PublishService {

  /**
   * A signed draft that isn't published within this time is discarded.
   */
  public const DRAFT_TTL = 86400;

  public const CATEGORIES = ['Operations', 'Demo (mocked data)', 'Data', 'Developer tools', 'Communication', 'Security', 'Other'];

  private const UPLOAD_DIR = 'private://toolgate/packages';

  public function __construct(
    private readonly EntityTypeManagerInterface $entityTypeManager,
    private readonly HelperClient $helper,
    private readonly FileRefResolver $refs,
    private readonly MarketplaceSettings $settings,
    private readonly FileSystemInterface $fileSystem,
    private readonly Connection $database,
    private readonly AccountInterface $currentUser,
    private readonly TimeInterface $time,
    private readonly LoggerInterface $logger,
    private readonly UuidInterface $uuid,
  ) {}

  public function publisherFor(AccountInterface $account): ?Publisher {
    if ($account->isAnonymous()) {
      return NULL;
    }
    $found = $this->entityTypeManager->getStorage('tgm_publisher')->loadByProperties(['uid' => $account->id()]);
    return $found ? reset($found) : NULL;
  }

  public function publisherBySlug(string $slug): ?Publisher {
    $found = $this->entityTypeManager->getStorage('tgm_publisher')->loadByProperties(['slug' => $slug]);
    return $found ? reset($found) : NULL;
  }

  /**
   * Why a slug can't be claimed, or NULL when it can.
   */
  public function slugProblem(string $slug, bool $system = FALSE): ?string {
    if (!preg_match(Publisher::SLUG_PATTERN, $slug)) {
      return 'Use 2 to 31 lowercase letters, digits or hyphens, starting with a letter.';
    }
    if (!$system && in_array($slug, $this->settings->reservedPrefixes(), TRUE)) {
      return 'This name is reserved.';
    }
    if ($this->publisherBySlug($slug)) {
      return 'This name is already taken.';
    }
    return NULL;
  }

  /**
   * Claims a slug for an account. The database unique key settles races.
   */
  public function createPublisher(AccountInterface $account, string $slug, array $values, bool $system = FALSE): Publisher {
    if ($this->publisherFor($account)) {
      throw new PublishException('PUBLISHER_EXISTS', 'This account already has a publisher profile.');
    }
    if ($problem = $this->slugProblem($slug, $system)) {
      throw new PublishException('SLUG', $problem);
    }
    $publisher = $this->entityTypeManager->getStorage('tgm_publisher')->create(['uid' => $account->id(), 'slug' => $slug] + $values);
    try {
      $publisher->save();
    }
    catch (EntityStorageException $e) {
      if ($e->getPrevious() instanceof IntegrityConstraintViolationException) {
        throw new PublishException('SLUG', 'This name is already taken.');
      }
      throw $e;
    }
    return $publisher;
  }

  /**
   * Stores an uploaded file privately and runs inspect, checks and signing.
   *
   * Returns the version as VALIDATED (signed draft) or REJECTED (with errors).
   * A retryable helper failure removes the upload and throws, so nothing is
   * left half-validated.
   */
  public function upload(string $sourcePath, string $filename): Version {
    $publisher = $this->publisherFor($this->currentUser);
    if (!$publisher) {
      throw new PublishException('NO_PUBLISHER', 'Create your publisher profile before uploading.');
    }
    if (!str_ends_with(strtolower($filename), '.tgpkg')) {
      throw new PublishException('ARCHIVE', 'Upload a .tgpkg package file.');
    }
    $size = @filesize($sourcePath);
    if ($size === FALSE || $size === 0) {
      throw new PublishException('ARCHIVE', 'The upload is empty.');
    }
    if ($size > $this->settings->maxPackageBytes()) {
      throw new PublishException('SIZE', sprintf('Packages can be at most %d MB.', $this->settings->get('max_package_mb')));
    }
    $directory = self::UPLOAD_DIR;
    $this->fileSystem->prepareDirectory($directory, FileSystemInterface::CREATE_DIRECTORY);
    $uri = $this->fileSystem->copy($sourcePath, $directory . '/' . $this->uuid->generate() . '.tgpkg', FileExists::Error);

    /** @var \Drupal\toolgate_marketplace\Entity\Version $version */
    $version = $this->entityTypeManager->getStorage('tgm_version')->create([
      'uid' => $this->currentUser->id(),
      'publisher' => $publisher->id(),
      'status' => Version::UPLOADED,
      'file_uri' => $uri,
      'size_bytes' => $size,
    ]);
    $version->save();
    try {
      return $this->validate($version, $publisher);
    }
    catch (PublishException | HelperException $e) {
      if ($version->status() !== Version::REJECTED) {
        $this->discard($version);
      }
      throw $e;
    }
  }

  private function validate(Version $version, Publisher $publisher): Version {
    $version->set('status', Version::VALIDATING)->save();
    $ref = $this->refs->toRef($version->get('file_uri')->value);
    $result = $this->helper->inspect($ref, NULL);
    $packageId = is_string($result['packageId'] ?? NULL) ? $result['packageId'] : NULL;
    $version->set('inspect', json_encode($result, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
    if (($result['valid'] ?? FALSE) !== TRUE || !$packageId) {
      return $this->reject($version, $result['errors'] ?? [['code' => 'INVALID', 'message' => 'The package is not valid.']]);
    }

    // Ownership comes from the logged-in account's publisher, never from the
    // form, the archive or the manifest's publisher text (design §5.2).
    $slug = $publisher->slug();
    $prefix = explode('.', $packageId, 2)[0];
    if ($prefix !== $slug) {
      return $this->reject($version, [['code' => 'PREFIX_MISMATCH', 'message' => "Package ids you publish must start with \"$slug.\"; this package is $packageId."]]);
    }
    if (in_array($prefix, $this->settings->reservedPrefixes(), TRUE) && !in_array($slug, $this->settings->systemPublishers(), TRUE)) {
      return $this->reject($version, [['code' => 'RESERVED_PREFIX', 'message' => "The prefix \"$prefix\" is reserved."]]);
    }
    $package = $this->package($packageId);
    if ($package && (int) $package->get('publisher')->target_id !== (int) $publisher->id()) {
      return $this->reject($version, [['code' => 'OWNERSHIP', 'message' => "$packageId belongs to another publisher."]]);
    }
    if ($this->versionExists($packageId, $result['version'])) {
      return $this->reject($version, [['code' => 'VERSION_EXISTS', 'message' => "$packageId {$result['version']} already exists. Published versions never change: raise the version and upload again."]]);
    }

    // The API diff and SemVer check against the newest available version.
    if ($previous = $this->previousAvailable($packageId)) {
      $result = $this->helper->inspect($ref, [
        'version' => $previous->version(),
        'fileRef' => $this->refs->toRef($previous->get('file_uri')->value),
      ]);
      $version->set('inspect', json_encode($result, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
      if (($result['valid'] ?? FALSE) !== TRUE || ($result['packageId'] ?? NULL) !== $packageId) {
        return $this->reject($version, $result['errors'] ?? [['code' => 'INVALID', 'message' => 'The package is not valid.']]);
      }
    }

    try {
      $release = $this->helper->sign($ref, $result['packageDigest'], $slug);
    }
    catch (HelperException $e) {
      if ($e->retryable) {
        throw $e;
      }
      return $this->reject($version, [['code' => $e->errorCode, 'message' => $e->getMessage()]]);
    }
    foreach (['packageId' => $packageId, 'version' => $result['version'], 'packageDigest' => $result['packageDigest'], 'archiveSha256' => $result['archiveSha256'], 'publisherSlug' => $slug, 'marketplace' => $this->settings->marketplaceId()] as $field => $expected) {
      if (($release[$field] ?? NULL) !== $expected) {
        throw new HelperException('SIGNING', "The signed release does not match the package ($field).", TRUE);
      }
    }

    $listing = $result['listing'] ?? [];
    $transaction = $this->database->startTransaction();
    try {
      $package ??= $this->createPackage($packageId, $publisher);
      $version->set('package', $package->id())
        ->set('package_id', $packageId)
        ->set('version', $result['version'])
        ->set('package_digest', $result['packageDigest'])
        ->set('descriptor_set_digest', $result['descriptorSetDigest'] ?? NULL)
        ->set('archive_sha256', $result['archiveSha256'])
        ->set('runtime', $result['runtime'] ?? NULL)
        ->set('api_change', $result['apiChange'] ?? 'none')
        ->set('release', json_encode($release, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE))
        ->set('errors', NULL)
        ->set('title', mb_substr((string) ($listing['title'] ?? $packageId), 0, 120))
        ->set('summary', mb_substr((string) ($listing['summary'] ?? ''), 0, 280))
        ->set('description', (string) ($listing['description'] ?? ''))
        ->set('license', $listing['license'] ?? NULL)
        ->set('category', in_array($listing['categories'][0] ?? NULL, self::CATEGORIES, TRUE) ? $listing['categories'][0] : NULL)
        ->set('tags', implode(', ', array_slice(array_filter($listing['tags'] ?? [], 'is_string'), 0, 10)))
        ->set('validated', $this->time->getCurrentTime())
        ->set('status', Version::VALIDATED);
      $version->save();
    }
    catch (EntityStorageException $e) {
      $transaction->rollBack();
      if ($e->getPrevious() instanceof IntegrityConstraintViolationException) {
        $version = $this->reloaded($version);
        return $this->reject($version, [['code' => 'VERSION_EXISTS', 'message' => "$packageId {$result['version']} already exists."]]);
      }
      throw $e;
    }
    unset($transaction);
    $this->logger->info('Validated and signed @id @version for publisher @slug.', ['@id' => $packageId, '@version' => $result['version'], '@slug' => $slug]);
    return $version;
  }

  /**
   * Saves the listing and makes the signed draft public (design §5.2 step 7).
   *
   * @param array<string, mixed> $listing
   *   Values for Version::LISTING_FIELDS only.
   */
  public function publish(Version $version, array $listing): Version {
    $publisher = $this->publisherFor($this->currentUser);
    if ($version->status() !== Version::VALIDATED) {
      throw new PublishException('STATE', 'Only a validated draft can be published.');
    }
    if (!$publisher || (int) $version->getOwnerId() !== (int) $this->currentUser->id()
      || (int) $version->get('publisher')->target_id !== (int) $publisher->id()) {
      throw new PublishException('OWNERSHIP', 'You can only publish your own drafts.');
    }
    if ($this->time->getCurrentTime() - (int) $version->get('validated')->value > self::DRAFT_TTL) {
      throw new PublishException('EXPIRED', 'This draft expired. Upload the package again.');
    }
    if (explode('.', $version->packageId(), 2)[0] !== $publisher->slug()) {
      throw new PublishException('PREFIX_MISMATCH', 'The package id no longer matches your publisher.');
    }
    $listing = array_intersect_key($listing, array_flip(Version::LISTING_FIELDS));
    if (trim((string) ($listing['title'] ?? $version->get('title')->value)) === '') {
      throw new PublishException('LISTING', 'A title is required.');
    }
    foreach (['repository_url', 'documentation_url'] as $field) {
      if (!empty($listing[$field]) && !Markdown::isHttps($listing[$field])) {
        throw new PublishException('LISTING', 'Links must be https:// URLs.');
      }
    }
    if (!empty($listing['category']) && !in_array($listing['category'], self::CATEGORIES, TRUE)) {
      throw new PublishException('LISTING', 'Choose a category from the list.');
    }
    // Publish only bytes that are still exactly the ones that were signed.
    $uri = $version->get('file_uri')->value;
    $hash = @hash_file('sha256', $uri);
    if ($hash !== $version->get('archive_sha256')->value) {
      $this->logger->error('Stored package for @id @version is missing or changed.', ['@id' => $version->packageId(), '@version' => $version->version()]);
      throw new PublishException('STORAGE', 'The stored package could not be verified. Upload it again.', TRUE);
    }
    foreach ($listing as $field => $value) {
      $version->set($field, is_string($value) ? trim($value) : $value);
    }
    $version->set('status', Version::AVAILABLE)
      ->set('published', $this->time->getCurrentTime())
      ->save();
    $this->logger->notice('Published @id @version.', ['@id' => $version->packageId(), '@version' => $version->version()]);
    return $version;
  }

  /**
   * Edits display-only listing fields of a published version.
   */
  public function updateListing(Version $version, array $listing): Version {
    foreach (['repository_url', 'documentation_url'] as $field) {
      if (!empty($listing[$field]) && !Markdown::isHttps($listing[$field])) {
        throw new PublishException('LISTING', 'Links must be https:// URLs.');
      }
    }
    foreach (array_intersect_key($listing, array_flip(Version::LISTING_FIELDS)) as $field => $value) {
      $version->set($field, is_string($value) ? trim($value) : $value);
    }
    $version->save();
    return $version;
  }

  public function delist(Version $version, string $reason): Version {
    if ($version->status() !== Version::AVAILABLE) {
      throw new PublishException('STATE', 'Only an available version can be delisted.');
    }
    $version->set('status', Version::DELISTED)
      ->set('delisted', $this->time->getCurrentTime())
      ->set('delist_reason', mb_substr($reason, 0, 500))
      ->save();
    $this->logger->warning('Delisted @id @version by user @uid: @reason', ['@id' => $version->packageId(), '@version' => $version->version(), '@uid' => $this->currentUser->id(), '@reason' => $reason]);
    return $version;
  }

  /**
   * Deletes an unpublished draft or rejected upload and its file.
   */
  public function discard(Version $version): void {
    if (in_array($version->status(), [Version::AVAILABLE, Version::DELISTED], TRUE)) {
      throw new PublishException('STATE', 'Published versions are kept; delist them instead.');
    }
    $uri = $version->get('file_uri')->value;
    if ($uri && file_exists($uri)) {
      $this->fileSystem->delete($uri);
    }
    $version->delete();
  }

  /**
   * Drops expired drafts and stale uploads, and rejected bytes (cron).
   */
  public function cleanUp(): int {
    $now = $this->time->getCurrentTime();
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $ids = $storage->getQuery()->accessCheck(FALSE)
      ->condition($storage->getQuery()->orConditionGroup()
        ->condition($storage->getQuery()->andConditionGroup()->condition('status', Version::VALIDATED)->condition('validated', $now - self::DRAFT_TTL, '<'))
        ->condition($storage->getQuery()->andConditionGroup()->condition('status', [Version::UPLOADED, Version::VALIDATING], 'IN')->condition('created', $now - 3600, '<'))
        ->condition($storage->getQuery()->andConditionGroup()->condition('status', Version::REJECTED)->condition('created', $now - self::DRAFT_TTL, '<')))
      ->execute();
    foreach ($storage->loadMultiple($ids) as $version) {
      $this->discard($version);
    }
    return count($ids);
  }

  /**
   * The highest AVAILABLE version of a package, for the API diff.
   */
  public function previousAvailable(string $packageId): ?Version {
    $latest = NULL;
    foreach ($this->versionsOf($packageId, [Version::AVAILABLE]) as $candidate) {
      if (!$latest || Catalog::compare($candidate->version(), $latest->version()) > 0) {
        $latest = $candidate;
      }
    }
    return $latest;
  }

  /**
   * @return \Drupal\toolgate_marketplace\Entity\Version[]
   */
  public function versionsOf(string $packageId, array $statuses): array {
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $ids = $storage->getQuery()->accessCheck(FALSE)
      ->condition('package_id', $packageId)
      ->condition('status', $statuses, 'IN')
      ->execute();
    return $storage->loadMultiple($ids);
  }

  public function findVersion(string $packageId, string $version): ?Version {
    $found = $this->entityTypeManager->getStorage('tgm_version')->loadByProperties(['package_id' => $packageId, 'version' => $version]);
    return $found ? reset($found) : NULL;
  }

  private function versionExists(string $packageId, string $version): bool {
    return $this->findVersion($packageId, $version) !== NULL;
  }

  private function package(string $packageId): ?Package {
    $found = $this->entityTypeManager->getStorage('tgm_package')->loadByProperties(['package_id' => $packageId]);
    return $found ? reset($found) : NULL;
  }

  private function createPackage(string $packageId, Publisher $publisher): Package {
    $package = $this->entityTypeManager->getStorage('tgm_package')->create(['package_id' => $packageId, 'publisher' => $publisher->id()]);
    $package->save();
    return $package;
  }

  private function reject(Version $version, array $errors): Version {
    $clean = [];
    foreach (array_slice($errors, 0, 50) as $error) {
      $clean[] = [
        'code' => mb_substr((string) ($error['code'] ?? 'INVALID'), 0, 40),
        'message' => mb_substr((string) ($error['message'] ?? ''), 0, 2000),
      ];
    }
    $version->set('status', Version::REJECTED)
      ->set('errors', json_encode($clean, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE))
      ->save();
    $this->logger->info('Rejected upload @vid: @codes', ['@vid' => $version->id(), '@codes' => implode(',', array_column($clean, 'code'))]);
    return $version;
  }

  private function reloaded(Version $version): Version {
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $storage->resetCache([$version->id()]);
    return $storage->load($version->id());
  }

}
