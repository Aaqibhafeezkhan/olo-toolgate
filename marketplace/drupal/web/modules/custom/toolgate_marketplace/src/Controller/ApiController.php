<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Controller;

use Drupal\Core\Cache\CacheableJsonResponse;
use Drupal\Core\Cache\CacheableMetadata;
use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\DependencyInjection\ContainerInjectionInterface;
use Drupal\toolgate_marketplace\Catalog;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\toolgate_marketplace\KeySet;
use Drupal\toolgate_marketplace\PublishService;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\StreamedResponse;

/**
 * The public API v1 (docs/api.md §1). No authentication, public data only.
 */
final class ApiController implements ContainerInjectionInterface {

  use AutowireTrait;

  public const PACKAGE_TYPE = 'application/vnd.ololabs.toolgate.package+zip';

  public function __construct(
    private readonly Catalog $catalog,
    private readonly PublishService $publishing,
    private readonly KeySet $keys,
    #[Autowire(service: 'logger.channel.toolgate_marketplace')]
    private readonly LoggerInterface $logger,
  ) {}

  public function catalog(Request $request): Response {
    $q = $request->query;
    $page = filter_var($q->get('page', 1), FILTER_VALIDATE_INT);
    $limit = filter_var($q->get('limit', 20), FILTER_VALIDATE_INT);
    $filters = [];
    foreach (['q', 'category', 'runtime', 'tag', 'publisher'] as $name) {
      $value = $q->get($name, '');
      if (!is_string($value) || mb_strlen($value) > 200) {
        return $this->error(400, 'INVALID_REQUEST', "$name is not valid.");
      }
      $filters[$name] = $value;
    }
    if ($page === FALSE || $page < 1 || $limit === FALSE || $limit < 1 || $limit > 100) {
      return $this->error(400, 'INVALID_REQUEST', 'page must be at least 1 and limit between 1 and 100.');
    }
    $items = $this->catalog->latest($filters);
    $body = [
      'page' => $page,
      'limit' => $limit,
      'total' => count($items),
      'items' => array_map([$this->catalog, 'catalogItem'], array_slice($items, ($page - 1) * $limit, $limit)),
    ];
    return $this->json($body, (new CacheableMetadata())->addCacheContexts(['url.query_args']));
  }

  public function package(string $package_id): Response {
    $versions = $this->catalog->versions($package_id);
    if (!$versions) {
      return $this->error(404, 'NOT_FOUND', 'No such package.');
    }
    $latest = NULL;
    foreach ($versions as $version) {
      if ($version->status() === Version::AVAILABLE) {
        $latest ??= $version;
      }
    }
    $body = [
      'packageId' => $package_id,
      'latestVersion' => $latest?->version(),
      'publisher' => $this->catalog->publisherSummary($versions[0]->publisher()),
      'versions' => array_map(fn(Version $v) => [
        'version' => $v->version(),
        'apiChange' => $v->get('api_change')->value ?: 'none',
        'compatibility' => $v->json('inspect')['compatibility'] ?? NULL,
        'publishedAt' => Catalog::time($v->get('published')->value),
        'delisted' => $v->status() === Version::DELISTED,
        'detailsUrl' => $this->catalog->url(Catalog::API . '/tools/' . $package_id . '/' . $v->version()),
      ], $versions),
    ];
    return $this->json($body);
  }

  public function version(string $package_id, string $version): Response {
    $found = $this->catalog->version($package_id, $version);
    return $found ? $this->json($this->catalog->versionDetail($found)) : $this->error(404, 'NOT_FOUND', 'No such version.');
  }

  /**
   * The exact signed bytes. Never a draft, a rejected upload or a delisted
   * version (design §5.2, §11).
   */
  public function download(Request $request, string $package_id, string $version): Response {
    $found = $this->catalog->version($package_id, $version);
    if (!$found) {
      return $this->error(404, 'NOT_FOUND', 'No such version.');
    }
    if ($found->status() === Version::DELISTED) {
      return $this->error(410, 'DELISTED', 'This version was delisted.');
    }
    $uri = $found->get('file_uri')->value;
    $sha = $found->get('archive_sha256')->value;
    $headers = [
      'Cache-Control' => 'public, max-age=300, must-revalidate',
      'ETag' => '"' . $sha . '"',
      'X-Content-Type-Options' => 'nosniff',
    ];
    if (trim((string) $request->headers->get('If-None-Match'), '" ') === $sha) {
      return new Response('', 304, $headers);
    }
    $handle = $uri ? @fopen($uri, 'rb') : FALSE;
    if ($handle === FALSE) {
      $this->logger->error('Package file for @id @version is missing.', ['@id' => $package_id, '@version' => $version]);
      return $this->error(503, 'UNAVAILABLE', 'The package file is temporarily unavailable.');
    }
    $filename = preg_replace('/[^a-z0-9.-]/', '-', $package_id . '-' . $version) . '.tgpkg';
    return new StreamedResponse(static function () use ($handle) {
      fpassthru($handle);
      fclose($handle);
    }, 200, $headers + [
      'Content-Type' => self::PACKAGE_TYPE,
      'Content-Length' => (string) $found->get('size_bytes')->value,
      'Content-Disposition' => 'attachment; filename="' . $filename . '"',
    ]);
  }

  public function release(string $package_id, string $version): Response {
    $found = $this->catalog->version($package_id, $version);
    if (!$found) {
      return $this->error(404, 'NOT_FOUND', 'No such version.');
    }
    if ($found->status() === Version::DELISTED) {
      return $this->error(410, 'DELISTED', 'This version was delisted.');
    }
    return $this->json($found->json('release'));
  }

  public function publisher(string $slug): Response {
    $publisher = $this->publishing->publisherBySlug($slug);
    return $publisher ? $this->json($this->catalog->publisher($publisher)) : $this->error(404, 'NOT_FOUND', 'No such publisher.');
  }

  public function media(string $media_id): Response {
    $file = $this->catalog->mediaFile($media_id);
    $type = $file?->getMimeType();
    if (!$file || !in_array($type, ['image/png', 'image/jpeg'], TRUE) || $file->getSize() > 1024 * 1024) {
      return $this->error(404, 'NOT_FOUND', 'No such media.');
    }
    $handle = @fopen($file->getFileUri(), 'rb');
    if ($handle === FALSE) {
      return $this->error(404, 'NOT_FOUND', 'No such media.');
    }
    return new StreamedResponse(static function () use ($handle) {
      fpassthru($handle);
      fclose($handle);
    }, 200, [
      'Content-Type' => $type,
      'Content-Length' => (string) $file->getSize(),
      'Cache-Control' => 'public, max-age=300, must-revalidate',
      'X-Content-Type-Options' => 'nosniff',
      'Content-Security-Policy' => "default-src 'none'",
    ]);
  }

  public function keys(): Response {
    $keys = $this->keys->get();
    if (!$keys) {
      return $this->error(503, 'UNAVAILABLE', 'The key set is not available yet.');
    }
    $response = new JsonResponse($keys);
    $response->setEncodingOptions(JSON_UNESCAPED_SLASHES);
    $response->headers->set('Cache-Control', 'public, max-age=300, must-revalidate');
    return $response;
  }

  private function json(array $body, ?CacheableMetadata $metadata = NULL): CacheableJsonResponse {
    $response = new CacheableJsonResponse($body);
    $response->setEncodingOptions(JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    $response->getCacheableMetadata()
      ->addCacheTags(['tgm_version_list', 'tgm_publisher_list', 'tgm_package_list'])
      ->setCacheMaxAge(300);
    if ($metadata) {
      $response->addCacheableDependency($metadata);
    }
    return $response;
  }

  private function error(int $status, string $code, string $message): Response {
    $response = new JsonResponse(['code' => $code, 'message' => $message], $status);
    $response->headers->set('Cache-Control', $status === 410 || $status === 404 ? 'public, max-age=60' : 'no-store');
    return $response;
  }

}
