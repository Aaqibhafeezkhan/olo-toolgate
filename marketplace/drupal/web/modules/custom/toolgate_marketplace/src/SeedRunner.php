<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Component\Datetime\TimeInterface;
use Drupal\Core\Entity\EntityTypeManagerInterface;
use Drupal\Core\Session\AccountSwitcherInterface;
use Drupal\Core\State\StateInterface;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\user\UserInterface;
use Psr\Log\LoggerInterface;

/**
 * Publishes the example packages through the normal path (prompt §3.2).
 *
 * The seed acts as the system account that owns the reserved publisher
 * "examples" and calls the same PublishService as a user upload: /inspect,
 * ownership and uniqueness, /sign, listing, AVAILABLE. Nothing is inserted
 * directly and nothing is unsigned.
 */
final class SeedRunner {

  public const STATUS_KEY = 'toolgate_marketplace.seed_status';
  public const SYSTEM_ACCOUNT = 'OLO Labs';
  public const PUBLISHER = 'examples';
  private const REPOSITORY = 'https://github.com/olo-labs/olo-toolgate/tree/main/';

  public function __construct(
    private readonly PublishService $publishing,
    private readonly EntityTypeManagerInterface $entityTypeManager,
    private readonly AccountSwitcherInterface $accountSwitcher,
    private readonly StateInterface $state,
    private readonly TimeInterface $time,
    private readonly LoggerInterface $logger,
  ) {}

  /**
   * @return array{seed: string, missing: list<array{id: string, reason: string}>, published: list<string>, skipped: list<string>, unexpected: list<string>, checkedAt: string}
   *
   * @throws \Drupal\toolgate_marketplace\HelperException
   *   When the helper is unreachable: the run stops and the status is kept.
   */
  public function run(string $dir, ?string $expectedFile = NULL): array {
    $manifest = self::readJson($dir . '/seed-manifest.json');
    $expected = self::readJson($expectedFile ?? $dir . '/expected-packages.json');
    $account = $this->systemAccount();
    $this->accountSwitcher->switchTo($account);
    $failures = [];
    $published = [];
    $skipped = [];
    $discovered = [];
    try {
      $publisher = $this->publishing->publisherFor($account) ?? $this->publishing->createPublisher($account, self::PUBLISHER, [
        'display_name' => 'ToolGate Examples',
        'organization' => 'OLO Labs',
        'website_url' => 'https://github.com/olo-labs/olo-toolgate/tree/main/examples',
        'bio' => 'Example tool packages from the ToolGate repository, built from source and signed at bring-up.',
      ], TRUE);
      if ($publisher->slug() !== self::PUBLISHER) {
        throw new \RuntimeException('The seed account owns a publisher other than "examples".');
      }
      foreach ($manifest['entries'] ?? [] as $entry) {
        $id = $entry['packageId'] ?? NULL;
        $label = $id ?? ($entry['folder'] ?? '?');
        if ($id) {
          $discovered[] = $id;
        }
        if (empty($entry['package'])) {
          $failures[$label] = $entry['error'] ?? 'the example build produced no package';
          continue;
        }
        try {
          $outcome = $this->seedOne($dir, $entry);
          if ($outcome === 'published') {
            $published[] = $id;
          }
          else {
            $skipped[] = $id;
          }
        }
        catch (HelperException $e) {
          if ($e->retryable) {
            throw $e;
          }
          $failures[$label] = $e->getMessage();
        }
        catch (PublishException | \RuntimeException $e) {
          $failures[$label] = $e->getMessage();
        }
        if (isset($failures[$label])) {
          $this->logger->error('Seed: @id failed: @reason', ['@id' => $label, '@reason' => $failures[$label]]);
        }
      }
    }
    finally {
      $this->accountSwitcher->switchBack();
    }

    $known = [];
    foreach ($expected['knownMissing'] ?? [] as $item) {
      $known[$item['id']] = $item['reason'] ?? 'known missing';
    }
    $missing = [];
    foreach (array_unique(array_merge($expected['required'] ?? [], array_keys($known))) as $id) {
      $available = array_filter($this->publishing->versionsOf($id, [Version::AVAILABLE]), static fn(Version $v) => $v->publisher()?->slug() === self::PUBLISHER);
      if (!$available) {
        $missing[] = ['id' => $id, 'reason' => $failures[$id] ?? $known[$id] ?? (in_array($id, $discovered, TRUE) ? 'not published' : 'not in the seed manifest')];
      }
    }
    $status = [
      'seed' => $missing ? 'degraded' : 'complete',
      'missing' => $missing,
      'published' => $published,
      'skipped' => $skipped,
      'unexpected' => array_values(array_diff($discovered, $expected['required'] ?? [], array_keys($known))),
      'checkedAt' => gmdate('Y-m-d\TH:i:s\Z', $this->time->getCurrentTime()),
    ];
    $this->state->set(self::STATUS_KEY, $status);
    return $status;
  }

  /**
   * @return string
   *   "published" or "skipped".
   */
  private function seedOne(string $dir, array $entry): string {
    $id = $entry['packageId'];
    $file = realpath($dir . '/' . $entry['package']);
    if ($file === FALSE || !str_starts_with($file, realpath($dir) . DIRECTORY_SEPARATOR) || !is_file($file)) {
      throw new \RuntimeException('the package file is missing from the seed');
    }
    $digest = self::packageDigest($file);
    $existing = $this->publishing->findVersion($id, (string) $entry['version']);
    if ($existing && $existing->get('package_digest')->value !== $digest) {
      throw new \RuntimeException(sprintf('%s %s already exists with a different packageDigest; left unchanged', $id, $entry['version']));
    }
    if ($existing && $existing->status() === Version::AVAILABLE) {
      return 'skipped';
    }
    if ($existing && $existing->status() === Version::DELISTED) {
      throw new \RuntimeException('delisted by an administrator; not republished');
    }
    $version = $existing ?? $this->publishing->upload($file, basename($file));
    if ($version->status() === Version::REJECTED) {
      $errors = $version->json('errors');
      throw new \RuntimeException('rejected: ' . implode('; ', array_map(static fn($e) => ($e['code'] ?? '') . ' ' . ($e['message'] ?? ''), $errors)));
    }
    $this->publishing->publish($version, $this->listing($entry, $version));
    return 'published';
  }

  private function listing(array $entry, Version $version): array {
    $readme = (string) ($entry['readme'] ?? '');
    $tags = [];
    foreach ($version->json('inspect')['tools'] ?? [] as $tool) {
      $tags[] = explode('.', (string) ($tool['name'] ?? ''), 2)[0];
    }
    $tags[] = 'example';
    if (!empty($entry['mocked'])) {
      $tags[] = 'mocked';
    }
    $summary = $readme !== '' ? Markdown::firstParagraph($readme) : '';
    return [
      'summary' => $summary !== '' ? $summary : $version->get('summary')->value,
      'description' => $readme !== '' ? $readme : $version->get('description')->value,
      'category' => $entry['category'] ?? NULL,
      'tags' => implode(', ', array_values(array_unique(array_filter($tags)))),
      'license' => 'Apache-2.0',
      'repository_url' => self::REPOSITORY . ltrim((string) ($entry['folder'] ?? 'examples'), '/'),
    ];
  }

  /**
   * The system account that owns "examples". It is blocked: nobody signs in
   * as it; only the seed acts for it.
   */
  private function systemAccount(): UserInterface {
    $storage = $this->entityTypeManager->getStorage('user');
    $found = $storage->loadByProperties(['name' => self::SYSTEM_ACCOUNT]);
    if ($found) {
      return reset($found);
    }
    $account = $storage->create(['name' => self::SYSTEM_ACCOUNT, 'status' => 0]);
    $account->save();
    return $account;
  }

  /**
   * sha256 over CHECKSUMS.sha256, the same packageDigest the helper computes.
   */
  public static function packageDigest(string $file): string {
    $zip = new \ZipArchive();
    if ($zip->open($file, \ZipArchive::RDONLY) !== TRUE) {
      throw new \RuntimeException('the package is not a readable archive');
    }
    $checksums = $zip->getFromName('CHECKSUMS.sha256');
    $zip->close();
    if ($checksums === FALSE) {
      throw new \RuntimeException('the package has no CHECKSUMS.sha256');
    }
    return 'sha256:' . hash('sha256', $checksums);
  }

  private static function readJson(string $path): array {
    $text = @file_get_contents($path);
    if ($text === FALSE) {
      throw new \RuntimeException("Cannot read $path");
    }
    return json_decode($text, TRUE, 64, JSON_THROW_ON_ERROR);
  }

  public function status(): ?array {
    return $this->state->get(self::STATUS_KEY);
  }

}
