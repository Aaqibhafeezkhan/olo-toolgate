<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\Tests\toolgate_marketplace\Functional;

use Drupal\Core\Entity\EntityStorageException;
use Drupal\Tests\BrowserTestBase;
use Drupal\Tests\toolgate_marketplace\Traits\PackageFixtures;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\user\UserInterface;
use PHPUnit\Framework\Attributes\Group;
use PHPUnit\Framework\Attributes\RunTestsInSeparateProcesses;

/**
 * The publish flow, public API and access rules, against the real helper.
 *
 * Covers design §12: ownership from the session only, the immutable slug,
 * XSS and Markdown, private drafts not downloadable, 410 after delisting, no
 * private fields in the API, duplicate versions, reserved prefixes,
 * malformed packages, the SemVer check against the previous version, and a
 * helper outage leaving nothing behind.
 */
#[Group('toolgate_marketplace')]
#[RunTestsInSeparateProcesses]
final class PublishingTest extends BrowserTestBase {

  use PackageFixtures;

  protected static $modules = ['toolgate_marketplace'];

  protected $defaultTheme = 'stark';

  private const API = '/api/toolgate/v1';

  protected function setUp(): void {
    parent::setUp();
    $this->configureHelper(self::env('MARKETPLACE_TEST_HELPER_URL'));
  }

  private function configureHelper(string $url): void {
    $root = realpath(self::env('MARKETPLACE_TEST_FILE_ROOT'));
    $private = realpath($this->siteDirectory) . '/private';
    $this->assertStringStartsWith($root . '/', $private, 'The helper file root must contain the test site.');
    $this->writeSettings(['settings' => ['toolgate_marketplace' => (object) [
      'value' => [
        'id' => 'olo-labs-marketplace',
        'base_url' => '',
        'helper_url' => $url,
        'helper_token' => self::env('MARKETPLACE_TEST_HELPER_TOKEN'),
        'file_refs' => 'file',
        'helper_file_root' => $root,
      ],
      'required' => TRUE,
    ]]]);
    $this->rebuildContainer();
  }

  private function publisherAccount(string $slug): UserInterface {
    $account = $this->drupalCreateUser(['publish toolgate packages']);
    $this->drupalLogin($account);
    $this->drupalGet('/my/profile');
    $this->submitForm(['slug' => $slug, 'display_name' => ucfirst($slug) . ' Inc', 'bio' => 'We build tools.'], 'Create profile');
    $this->assertSession()->pageTextContains('Your publisher profile is saved.');
    return $account;
  }

  private function upload(string $file): void {
    $this->drupalGet('/publish');
    $this->submitForm(['files[package]' => $file], 'Upload and check');
  }

  private function latestVersion(): Version {
    $storage = \Drupal::entityTypeManager()->getStorage('tgm_version');
    $storage->resetCache();
    $ids = $storage->getQuery()->accessCheck(FALSE)->sort('id', 'DESC')->range(0, 1)->execute();
    return $storage->load(reset($ids));
  }

  private function getJson(string $path, int $status = 200): array {
    [$path, $query] = array_pad(explode('?', $path, 2), 2, '');
    parse_str($query, $options);
    $this->drupalGet($path, ['query' => $options]);
    $this->assertSession()->statusCodeEquals($status);
    return json_decode($this->getSession()->getPage()->getContent(), TRUE) ?? [];
  }

  public function testPublishFlowAndPublicApi(): void {
    $this->drupalGet('/publish');
    $this->assertSession()->statusCodeEquals(403);

    $alice = $this->publisherAccount('acme');
    // The slug is never editable after creation.
    $this->drupalGet('/my/profile');
    $this->assertSession()->fieldNotExists('slug');
    $this->assertSession()->pageTextContains('can never be changed');

    // 1 Upload, 2 Checks.
    $file = self::pack();
    $this->upload($file);
    $this->assertSession()->pageTextContains('All checks passed and the package is signed');
    $draft = $this->latestVersion();
    $this->assertSame(Version::VALIDATED, $draft->status());
    $this->assertSame('acme.notify', $draft->packageId());

    // A signed draft is private: not in the API, not downloadable.
    $this->drupalLogout();
    $this->getJson(self::API . '/tools/acme.notify/0.1.0', 404);
    $this->getJson(self::API . '/tools/acme.notify/0.1.0/package', 404);
    $this->getJson(self::API . '/tools/acme.notify/0.1.0/release', 404);
    $this->drupalGet('/publish/' . $draft->id() . '/checks');
    $this->assertSession()->statusCodeEquals(403);
    $this->assertSame(0, $this->getJson(self::API . '/catalog')['total']);

    // 3 Describe, 4 Publish, with hostile Markdown.
    $this->drupalLogin($alice);
    $this->drupalGet('/publish/' . $draft->id() . '/describe');
    $this->submitForm([
      'title' => 'Acme notify',
      'summary' => 'Sends notifications.',
      'description' => "# Hello\n\n<script>alert('x')</script>\n\n[bad](javascript:alert(1)) [good](https://example.com) <img src=x onerror=alert(2)>",
      'category' => 'Communication',
      'tags' => 'notify, alerts',
      'repository_url' => 'https://github.com/acme/notify',
    ], 'Publish');
    $this->assertSession()->pageTextContains('acme.notify 0.1.0 is published.');
    $html = $this->getSession()->getPage()->getContent();
    $this->assertStringNotContainsString("alert('x')", $html);
    $this->assertStringNotContainsString('javascript:', $html);
    $this->assertStringNotContainsString('onerror', $html);
    $this->assertStringContainsString('href="https://example.com" rel="nofollow noopener noreferrer ugc"', $html);

    // Public API.
    $this->drupalLogout();
    $catalog = $this->getJson(self::API . '/catalog?q=notify');
    $this->assertSame(1, $catalog['total']);
    $this->assertSame('acme.notify', $catalog['items'][0]['packageId']);
    $detail = $this->getJson(self::API . '/tools/acme.notify/0.1.0');
    $this->assertSame('acme', $detail['publisher']['slug']);
    $this->assertSame('Communication', $detail['category']);
    $this->assertSame(['notify', 'alerts'], $detail['tags']);
    $this->assertFalse($detail['delisted']);
    $this->assertSame('none', $detail['apiChange']);
    $this->assertSame('notify.send', $detail['tools'][0]['name']);

    // Exact bytes, headers, and a signature that verifies with the key set.
    $this->drupalGet(self::API . '/tools/acme.notify/0.1.0/package');
    $this->assertSession()->statusCodeEquals(200);
    $bytes = (string) $this->getHttpClient()->get($this->buildUrl(self::API . '/tools/acme.notify/0.1.0/package'))->getBody();
    $this->assertSame(hash_file('sha256', $file), hash('sha256', $bytes), sprintf('%d bytes vs %d', strlen($bytes), filesize($file)));
    $this->assertSession()->responseHeaderEquals('Content-Type', 'application/vnd.ololabs.toolgate.package+zip');
    $this->assertSession()->responseHeaderContains('Cache-Control', 'max-age=300');
    $this->assertSession()->responseHeaderContains('Cache-Control', 'must-revalidate');
    $this->assertSession()->responseHeaderEquals('ETag', '"' . $detail['archiveSha256'] . '"');
    $release = $this->getJson(self::API . '/tools/acme.notify/0.1.0/release');
    $this->assertSame('olo-labs-marketplace', $release['marketplace']);
    $this->assertSame('acme', $release['publisherSlug']);
    $this->assertSame($detail['packageDigest'], $release['packageDigest']);
    $keys = $this->getJson('/.well-known/toolgate-marketplace-keys.json');
    $key = array_values(array_filter($keys['keys'], static fn($k) => $k['keyId'] === $release['keyId']))[0];
    $signature = base64_decode($release['signature']);
    unset($release['signature']);
    $this->assertTrue(sodium_crypto_sign_verify_detached($signature, self::canonical($release), base64_decode($key['publicKey'])));
    $release['version'] = '9.9.9';
    $this->assertFalse(sodium_crypto_sign_verify_detached($signature, self::canonical($release), base64_decode($key['publicKey'])));

    // Publisher profile: public fields only.
    $publisher = $this->getJson(self::API . '/publishers/acme');
    $this->assertEqualsCanonicalizing(
      ['slug', 'displayName', 'organization', 'avatarMediaId', 'profileUrl', 'bio', 'websiteUrl', 'githubUrl', 'linkedinUrl', 'country', 'publicContactEmail', 'packages'],
      array_keys($publisher),
    );
    $this->assertStringNotContainsString($alice->getEmail(), json_encode($publisher));
    $this->assertStringNotContainsString($alice->getEmail(), json_encode($detail));

    // HTML pages.
    $this->drupalGet('/tools/acme.notify');
    $this->assertSession()->statusCodeEquals(200);
    $this->assertSession()->pageTextContains('Acme notify');
    $this->assertSession()->pageTextContains('WebhookApiKey');
    $this->drupalGet('/publishers/acme');
    $this->assertSession()->pageTextContains('Acme Inc');
    $this->drupalGet('/tools', ['query' => ['category' => 'Communication']]);
    $this->assertSession()->pageTextContains('1 tool');

    // The same version can't be uploaded twice.
    $this->drupalLogin($alice);
    $this->upload(self::pack());
    $this->assertSession()->pageTextContains('already exists');
    $this->assertSame(Version::REJECTED, $this->latestVersion()->status());

    // A patch release with a breaking change is refused by the SemVer check.
    $this->upload(self::pack(static function (array &$m) {
      $m['tools'][0]['descriptor']['inputSchema']['required'][] = 'severity';
    }, version: '0.1.1'));
    $this->assertSession()->pageTextContains('SEMVER');
    // The same change as a minor release passes and is marked breaking.
    $this->upload(self::pack(static function (array &$m) {
      $m['tools'][0]['descriptor']['inputSchema']['required'][] = 'severity';
    }, version: '0.2.0'));
    $this->assertSession()->pageTextContains('All checks passed');
    $this->assertSame('breaking', $this->latestVersion()->get('api_change')->value);

    // Delisting: 410 for bytes and release, still visible as delisted.
    $published = \Drupal::service('Drupal\toolgate_marketplace\PublishService')->findVersion('acme.notify', '0.1.0');
    $this->drupalGet('/my/packages/' . $published->id() . '/delist');
    $this->submitForm(['reason' => 'Superseded'], 'Delist');
    $this->drupalLogout();
    $this->getJson(self::API . '/tools/acme.notify/0.1.0/package', 410);
    $this->assertSame('DELISTED', $this->getJson(self::API . '/tools/acme.notify/0.1.0/release', 410)['code']);
    $detail = $this->getJson(self::API . '/tools/acme.notify/0.1.0');
    $this->assertTrue($detail['delisted']);
    $this->assertNull($detail['downloadUrl']);
    $this->assertSame(0, $this->getJson(self::API . '/catalog')['total']);
    $this->assertTrue($this->getJson(self::API . '/tools/acme.notify')['versions'][0]['delisted']);
  }

  public function testOwnershipComesFromTheSession(): void {
    $alice = $this->publisherAccount('acme');
    $this->upload(self::pack());
    $draft = $this->latestVersion();

    // Bob's slug is "bob": an acme.* package is refused before signing.
    $this->publisherAccount('bob');
    $this->upload(self::pack(id: 'acme.other'));
    $this->assertSession()->pageTextContains('must start with "bob."');
    $rejected = $this->latestVersion();
    $this->assertSame(Version::REJECTED, $rejected->status());
    $this->assertNull($rejected->packageId());
    $this->assertSame('', (string) $rejected->get('release')->value);

    // Bob can't see, describe, delist or discard Alice's draft.
    foreach (['/publish/%d/checks', '/publish/%d/describe', '/my/packages/%d/delist', '/my/packages/%d/discard'] as $path) {
      $this->drupalGet(sprintf($path, $draft->id()));
      $this->assertSession()->statusCodeEquals(403);
    }

    // Reserved slugs can't be claimed.
    $carol = $this->drupalCreateUser(['publish toolgate packages']);
    $this->drupalLogin($carol);
    foreach (['examples', 'toolgate', 'olo'] as $slug) {
      $this->drupalGet('/my/profile');
      $this->submitForm(['slug' => $slug, 'display_name' => 'Carol'], 'Create profile');
      $this->assertSession()->pageTextContains('This name is reserved.');
    }
    $this->drupalGet('/my/profile');
    $this->submitForm(['slug' => 'acme', 'display_name' => 'Carol'], 'Create profile');
    $this->assertSession()->pageTextContains('This name is already taken.');

    // The slug can't change, even through the entity API.
    $publisher = \Drupal::service('Drupal\toolgate_marketplace\PublishService')->publisherFor($alice);
    $publisher->set('slug', 'acme2');
    $this->expectException(EntityStorageException::class);
    $this->expectExceptionMessage('A publisher slug is immutable.');
    $publisher->save();
  }

  public function testMalformedPackagesAndHelperOutage(): void {
    $this->publisherAccount('acme');

    $this->upload(self::garbage());
    $this->assertSession()->pageTextContains('This package was not accepted');
    $this->assertSame(Version::REJECTED, $this->latestVersion()->status());

    $text = sys_get_temp_dir() . '/tgm-not-a-package.txt';
    file_put_contents($text, 'hello');
    $this->upload($text);
    $this->assertSession()->pageTextContains('Upload a .tgpkg package file.');

    // A reserved prefix is refused even when it matches nobody's slug.
    $this->upload(self::pack(id: 'toolgate.notify'));
    $this->assertSession()->pageTextContains('must start with "acme."');

    // The helper is down: a retryable error and nothing left behind.
    $count = \Drupal::entityTypeManager()->getStorage('tgm_version')->getQuery()->accessCheck(FALSE)->count()->execute();
    $this->configureHelper('http://127.0.0.1:9');
    $this->upload(self::pack(version: '1.0.0'));
    $this->assertSession()->pageTextContains('Nothing was published; try again.');
    $this->assertSame($count, \Drupal::entityTypeManager()->getStorage('tgm_version')->getQuery()->accessCheck(FALSE)->count()->execute());
  }

}
