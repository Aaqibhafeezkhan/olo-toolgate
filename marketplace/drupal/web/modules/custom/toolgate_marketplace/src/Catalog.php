<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Composer\Semver\Comparator;
use Drupal\Core\Entity\EntityTypeManagerInterface;
use Drupal\file\FileInterface;
use Drupal\toolgate_marketplace\Entity\Publisher;
use Drupal\toolgate_marketplace\Entity\Version;
use Symfony\Component\HttpFoundation\RequestStack;

/**
 * Reads the public catalog and shapes it as docs/api.md §1 objects.
 *
 * The HTML pages and the JSON API both read through here, so the two always
 * show the same public data and never a private field.
 */
final class Catalog {

  public const API = '/api/toolgate/v1';

  public function __construct(
    private readonly EntityTypeManagerInterface $entityTypeManager,
    private readonly MarketplaceSettings $settings,
    private readonly RequestStack $requests,
  ) {}

  /**
   * The newest AVAILABLE version of each package, filtered.
   *
   * @param array{q?: string, category?: string, runtime?: string, tag?: string, publisher?: string} $filters
   *
   * @return list<\Drupal\toolgate_marketplace\Entity\Version>
   */
  public function latest(array $filters = [], string $sort = 'newest'): array {
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $query = $storage->getQuery()->accessCheck(FALSE)->condition('status', Version::AVAILABLE);
    if (!empty($filters['publisher'])) {
      $query->condition('publisher.entity.slug', $filters['publisher']);
    }
    if (!empty($filters['runtime'])) {
      $query->condition('runtime', $filters['runtime']);
    }
    $latest = [];
    foreach ($storage->loadMultiple($query->execute()) as $version) {
      $id = $version->packageId();
      if (!isset($latest[$id]) || self::compare($version->version(), $latest[$id]->version()) > 0) {
        $latest[$id] = $version;
      }
    }
    $q = mb_strtolower(trim($filters['q'] ?? ''));
    $items = array_filter($latest, function (Version $v) use ($filters, $q) {
      if (!empty($filters['category']) && $v->get('category')->value !== $filters['category']) {
        return FALSE;
      }
      if (!empty($filters['tag']) && !in_array(mb_strtolower($filters['tag']), array_map('mb_strtolower', $v->tags()), TRUE)) {
        return FALSE;
      }
      if ($q !== '') {
        $haystack = mb_strtolower(implode(' ', [$v->packageId(), $v->get('title')->value, $v->get('summary')->value, $v->get('tags')->value, $v->publisher()?->displayName()]));
        foreach (preg_split('/\s+/', $q) as $word) {
          if (!str_contains($haystack, $word)) {
            return FALSE;
          }
        }
      }
      return TRUE;
    });
    usort($items, $sort === 'name'
      ? static fn(Version $a, Version $b) => strnatcasecmp((string) $a->get('title')->value, (string) $b->get('title')->value)
      : static fn(Version $a, Version $b) => [(int) $b->get('published')->value, $a->packageId()] <=> [(int) $a->get('published')->value, $b->packageId()]);
    return array_values($items);
  }

  /**
   * Every AVAILABLE and DELISTED version of a package, newest first.
   *
   * @return list<\Drupal\toolgate_marketplace\Entity\Version>
   */
  public function versions(string $packageId): array {
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $ids = $storage->getQuery()->accessCheck(FALSE)
      ->condition('package_id', $packageId)
      ->condition('status', [Version::AVAILABLE, Version::DELISTED], 'IN')
      ->execute();
    $versions = array_values($storage->loadMultiple($ids));
    usort($versions, static fn(Version $a, Version $b) => self::compare($b->version(), $a->version()));
    return $versions;
  }

  /**
   * A public version (AVAILABLE or DELISTED), or NULL.
   */
  public function version(string $packageId, string $version): ?Version {
    $found = $this->entityTypeManager->getStorage('tgm_version')->loadByProperties(['package_id' => $packageId, 'version' => $version]);
    $found = $found ? reset($found) : NULL;
    return $found && in_array($found->status(), [Version::AVAILABLE, Version::DELISTED], TRUE) ? $found : NULL;
  }

  /**
   * @return array<string, int> category => number of packages
   */
  public function categories(): array {
    $counts = array_fill_keys(PublishService::CATEGORIES, 0);
    foreach ($this->latest() as $version) {
      $category = $version->get('category')->value ?: 'Other';
      $counts[$category] = ($counts[$category] ?? 0) + 1;
    }
    return array_filter($counts);
  }

  public function catalogItem(Version $version): array {
    $inspect = $version->json('inspect');
    $id = $version->packageId();
    return [
      'packageId' => $id,
      'version' => $version->version(),
      'title' => (string) $version->get('title')->value,
      'summary' => (string) $version->get('summary')->value,
      'category' => $version->get('category')->value,
      'tags' => $version->tags(),
      'runtime' => $version->get('runtime')->value,
      'signed' => $version->get('release')->value !== NULL,
      'compatibility' => $inspect['compatibility'] ?? NULL,
      'publisher' => $this->publisherSummary($version->publisher()),
      'iconMediaId' => NULL,
      'detailsUrl' => $this->url('/tools/' . $id . '/' . $version->version()),
    ];
  }

  public function versionDetail(Version $version): array {
    $inspect = $version->json('inspect');
    $id = $version->packageId();
    $delisted = $version->status() === Version::DELISTED;
    $api = $this->url(self::API . '/tools/' . $id . '/' . $version->version());
    $screenshots = [];
    foreach ($version->get('screenshots') as $item) {
      if ($item->entity) {
        $screenshots[] = self::mediaId($item->entity);
      }
    }
    return $this->catalogItem($version) + [
      'description' => (string) $version->get('description')->value,
      'repositoryUrl' => $this->https($version->get('repository_url')->value),
      'documentationUrl' => $this->https($version->get('documentation_url')->value),
      'license' => $version->get('license')->value,
      'screenshots' => $screenshots,
      'tools' => $inspect['tools'] ?? [],
      'requiredSettings' => $inspect['requiredSettings'] ?? [],
      'packageDigest' => $version->get('package_digest')->value,
      'descriptorSetDigest' => $version->get('descriptor_set_digest')->value,
      'archiveSha256' => $version->get('archive_sha256')->value,
      'apiChange' => $version->get('api_change')->value ?: 'none',
      'delisted' => $delisted,
      'publishedAt' => self::time($version->get('published')->value),
      'downloadUrl' => $delisted ? NULL : $api . '/package',
      'releaseUrl' => $delisted ? NULL : $api . '/release',
      'publisher' => $this->publisher($version->publisher(), FALSE),
    ];
  }

  public function publisherSummary(?Publisher $publisher): ?array {
    if (!$publisher) {
      return NULL;
    }
    $avatar = $publisher->get('avatar')->entity;
    return [
      'slug' => $publisher->slug(),
      'displayName' => $publisher->displayName(),
      'organization' => $publisher->get('organization')->value,
      'avatarMediaId' => $avatar ? self::mediaId($avatar) : NULL,
      'profileUrl' => $this->url('/publishers/' . $publisher->slug()),
    ];
  }

  /**
   * The public profile: never the login email, roles or account fields.
   */
  public function publisher(?Publisher $publisher, bool $withPackages = TRUE): ?array {
    if (!$publisher) {
      return NULL;
    }
    $profile = $this->publisherSummary($publisher) + [
      'bio' => $publisher->get('bio')->value,
      'websiteUrl' => $this->https($publisher->get('website_url')->value),
      'githubUrl' => $this->https($publisher->get('github_url')->value),
      'linkedinUrl' => $this->https($publisher->get('linkedin_url')->value),
      'country' => $publisher->get('country')->value,
      'publicContactEmail' => $publisher->get('public_contact_email')->value,
    ];
    if ($withPackages) {
      $profile['packages'] = array_map([$this, 'catalogItem'], $this->latest(['publisher' => $publisher->slug()]));
    }
    return $profile;
  }

  /**
   * SemVer precedence; build metadata is ignored as the spec says.
   */
  public static function compare(string $a, string $b): int {
    $a = explode('+', $a, 2)[0];
    $b = explode('+', $b, 2)[0];
    return Comparator::greaterThan($a, $b) ? 1 : (Comparator::lessThan($a, $b) ? -1 : 0);
  }

  public static function mediaId(FileInterface $file): string {
    return 'm_' . str_replace('-', '', $file->uuid());
  }

  /**
   * The public file behind a media id, if it is an avatar or a screenshot of
   * a public version.
   */
  public function mediaFile(string $mediaId): ?FileInterface {
    if (!preg_match('/^m_([0-9a-f]{32})$/', $mediaId, $m)) {
      return NULL;
    }
    $uuid = vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split($m[1], 4));
    $files = $this->entityTypeManager->getStorage('file')->loadByProperties(['uuid' => $uuid]);
    $file = $files ? reset($files) : NULL;
    if (!$file instanceof FileInterface) {
      return NULL;
    }
    $publishers = $this->entityTypeManager->getStorage('tgm_publisher')->getQuery()->accessCheck(FALSE)
      ->condition('avatar.target_id', $file->id())->count()->execute();
    $versions = $this->entityTypeManager->getStorage('tgm_version')->getQuery()->accessCheck(FALSE)
      ->condition('screenshots.target_id', $file->id())
      ->condition('status', Version::AVAILABLE)->count()->execute();
    return ($publishers || $versions) ? $file : NULL;
  }

  /**
   * An absolute public URL, from MARKETPLACE_BASE_URL when it is set.
   */
  public function url(string $path): string {
    $base = $this->settings->baseUrl();
    if ($base === '') {
      $request = $this->requests->getCurrentRequest();
      $base = $request ? $request->getSchemeAndHttpHost() . $request->getBasePath() : '';
    }
    return $base . $path;
  }

  private function https(?string $url): ?string {
    return Markdown::isHttps($url) ? $url : NULL;
  }

  public static function time(?string $timestamp): ?string {
    return $timestamp ? gmdate('Y-m-d\TH:i:s\Z', (int) $timestamp) : NULL;
  }

}
