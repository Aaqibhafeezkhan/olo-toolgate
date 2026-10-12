<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Controller;

use Drupal\Core\Cache\CacheableMetadata;
use Drupal\Core\Datetime\DateFormatterInterface;
use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\DependencyInjection\ContainerInjectionInterface;
use Drupal\Core\Entity\EntityTypeManagerInterface;
use Drupal\Core\Pager\PagerManagerInterface;
use Drupal\Core\Session\AccountInterface;
use Drupal\Core\StringTranslation\StringTranslationTrait;
use Drupal\Core\StringTranslation\ByteSizeMarkup;
use Drupal\Core\Url;
use Drupal\toolgate_marketplace\Catalog;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\toolgate_marketplace\KeySet;
use Drupal\toolgate_marketplace\Markdown;
use Drupal\toolgate_marketplace\MarketplaceSettings;
use Drupal\toolgate_marketplace\PublishService;
use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * The public and publisher pages (prompt §6.4).
 */
final class PageController implements ContainerInjectionInterface {

  use AutowireTrait;
  use StringTranslationTrait;

  private const PER_PAGE = 24;

  public function __construct(
    private readonly Catalog $catalog,
    private readonly PublishService $publishing,
    private readonly KeySet $keys,
    private readonly MarketplaceSettings $settings,
    private readonly EntityTypeManagerInterface $entityTypeManager,
    private readonly PagerManagerInterface $pagerManager,
    private readonly AccountInterface $currentUser,
    private readonly DateFormatterInterface $dateFormatter,
  ) {}

  public function home(): array {
    $latest = $this->catalog->latest();
    $examples = array_filter($latest, static fn(Version $v) => $v->publisher()?->slug() === 'examples');
    $build = [
      '#theme' => 'tgm_home',
      '#search' => ['action' => Url::fromRoute('toolgate_marketplace.browse')->toString()],
      '#categories' => array_map(fn($category, $count) => [
        'name' => $category,
        'count' => $count,
        'url' => Url::fromRoute('toolgate_marketplace.browse', [], ['query' => ['category' => $category]])->toString(),
      ], array_keys($this->catalog->categories()), $this->catalog->categories()),
      '#newest' => array_map([$this, 'card'], array_slice($latest, 0, 6)),
      '#examples' => array_map([$this, 'card'], array_slice(array_values($examples), 0, 6)),
    ];
    return $this->listCache($build);
  }

  public function browse(Request $request): array {
    $q = $request->query;
    $filters = [];
    foreach (['q', 'category', 'runtime', 'tag', 'publisher'] as $name) {
      $value = $q->get($name, '');
      $filters[$name] = is_string($value) ? mb_substr(trim($value), 0, 200) : '';
    }
    $sort = $q->get('sort') === 'name' ? 'name' : 'newest';
    $items = $this->catalog->latest($filters, $sort);

    // Facet counts over the current result set.
    $facets = ['category' => [], 'runtime' => [], 'tag' => [], 'publisher' => []];
    foreach ($items as $version) {
      $facets['category'][$version->get('category')->value ?: 'Other'] = ($facets['category'][$version->get('category')->value ?: 'Other'] ?? 0) + 1;
      $runtime = (string) $version->get('runtime')->value;
      $facets['runtime'][$runtime] = ($facets['runtime'][$runtime] ?? 0) + 1;
      foreach ($version->tags() as $tag) {
        $facets['tag'][$tag] = ($facets['tag'][$tag] ?? 0) + 1;
      }
      $slug = (string) $version->publisher()?->slug();
      $facets['publisher'][$slug] = ($facets['publisher'][$slug] ?? 0) + 1;
    }
    $facetLinks = [];
    foreach ($facets as $name => $values) {
      arsort($values);
      foreach (array_slice($values, 0, 20, TRUE) as $value => $count) {
        $active = $filters[$name] === (string) $value;
        $query = array_filter([...$filters, 'sort' => $sort === 'name' ? 'name' : '', $name => $active ? '' : (string) $value]);
        $facetLinks[$name][] = [
          'label' => (string) $value,
          'count' => $count,
          'active' => $active,
          'url' => Url::fromRoute('toolgate_marketplace.browse', [], ['query' => $query])->toString(),
        ];
      }
    }

    $pager = $this->pagerManager->createPager(count($items), self::PER_PAGE);
    $page = array_slice($items, $pager->getCurrentPage() * self::PER_PAGE, self::PER_PAGE);
    $build = [
      '#theme' => 'tgm_browse',
      '#form' => [
        'action' => Url::fromRoute('toolgate_marketplace.browse')->toString(),
        'values' => $filters + ['sort' => $sort],
        'categories' => PublishService::CATEGORIES,
      ],
      '#facets' => $facetLinks,
      '#results' => array_map([$this, 'card'], $page),
      '#total' => count($items),
      '#pager' => ['#type' => 'pager'],
      '#clear_url' => Url::fromRoute('toolgate_marketplace.browse')->toString(),
      '#filtered' => (bool) array_filter($filters),
      '#attached' => ['library' => ['toolgate_marketplace/browse']],
    ];
    $build['#cache']['contexts'][] = 'url.query_args';
    return $this->listCache($build);
  }

  public function tool(string $package_id, ?string $version = NULL): array {
    $versions = $this->catalog->versions($package_id);
    if (!$versions) {
      throw new NotFoundHttpException();
    }
    if ($version === NULL) {
      $current = NULL;
      foreach ($versions as $candidate) {
        if ($candidate->status() === Version::AVAILABLE) {
          $current = $candidate;
          break;
        }
      }
      $current ??= $versions[0];
    }
    else {
      $current = $this->catalog->version($package_id, $version) ?? throw new NotFoundHttpException();
    }
    $detail = $this->catalog->versionDetail($current);
    $ownerLinks = [];
    if ($current->access('update', $this->currentUser)) {
      $ownerLinks[] = ['title' => $this->t('Edit listing'), 'url' => Url::fromRoute('toolgate_marketplace.version_edit', ['tgm_version' => $current->id()])->toString()];
      if ($current->status() === Version::AVAILABLE) {
        $ownerLinks[] = ['title' => $this->t('Delist this version'), 'url' => Url::fromRoute('toolgate_marketplace.version_delist', ['tgm_version' => $current->id()])->toString()];
      }
    }
    $build = [
      '#theme' => 'tgm_tool',
      '#detail' => $detail,
      '#description' => ['#markup' => Markdown::toHtml($detail['description'])],
      '#compat' => self::compatHint($detail['compatibility']),
      '#versions' => array_map(fn(Version $v) => [
        'version' => $v->version(),
        'current' => $v->id() === $current->id(),
        'delisted' => $v->status() === Version::DELISTED,
        'apiChange' => $v->get('api_change')->value ?: 'none',
        'compat' => self::compatHint($v->json('inspect')['compatibility'] ?? NULL),
        'publishedAt' => $v->get('published')->value,
        'url' => Url::fromRoute('toolgate_marketplace.tool_version', ['package_id' => $package_id, 'version' => $v->version()])->toString(),
      ], $versions),
      '#key' => $this->keys->find($current->json('release')['keyId'] ?? ''),
      '#use_box' => json_encode([
        'marketplace' => $this->settings->marketplaceId(),
        'packageId' => $detail['packageId'],
        'version' => $detail['version'],
        'packageDigest' => $detail['packageDigest'],
      ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES),
      '#avatar' => $this->avatarUrl($detail['publisher']),
      '#owner_links' => $ownerLinks,
      '#attached' => ['library' => ['toolgate_marketplace/tool']],
    ];
    $build['#cache']['contexts'][] = 'user';
    return $this->listCache($build);
  }

  public function toolTitle(string $package_id, ?string $version = NULL): string {
    $versions = $this->catalog->versions($package_id);
    $found = $version ? $this->catalog->version($package_id, $version) : ($versions[0] ?? NULL);
    return $found ? (string) $found->get('title')->value : $package_id;
  }

  public function publishers(): array {
    $storage = $this->entityTypeManager->getStorage('tgm_publisher');
    $cards = [];
    foreach ($storage->loadMultiple($storage->getQuery()->accessCheck(FALSE)->sort('display_name')->execute()) as $publisher) {
      $profile = $this->catalog->publisher($publisher);
      if (!$profile['packages']) {
        continue;
      }
      $cards[] = [
        '#type' => 'html_tag',
        '#tag' => 'a',
        '#attributes' => ['class' => ['tgm-publisher-card'], 'href' => Url::fromRoute('toolgate_marketplace.publisher', ['slug' => $profile['slug']])->toString()],
        'name' => ['#plain_text' => $profile['displayName']],
        'meta' => ['#type' => 'html_tag', '#tag' => 'span', '#value' => $this->formatPlural(count($profile['packages']), '1 tool', '@count tools')],
      ];
    }
    $build = ['#type' => 'container', '#attributes' => ['class' => ['tgm-publisher-grid']], 'cards' => $cards];
    if (!$cards) {
      $build['empty'] = ['#markup' => '<p>' . $this->t('No publishers yet.') . '</p>'];
    }
    return $this->listCache($build);
  }

  public function publisher(string $slug): array {
    $publisher = $this->publishing->publisherBySlug($slug) ?? throw new NotFoundHttpException();
    $profile = $this->catalog->publisher($publisher);
    $build = [
      '#theme' => 'tgm_publisher',
      '#profile' => $profile,
      '#bio' => ['#markup' => Markdown::toHtml((string) $profile['bio'])],
      '#avatar' => $this->avatarUrl($profile),
      '#cards' => array_map([$this, 'card'], $this->catalog->latest(['publisher' => $slug])),
    ];
    return $this->listCache($build);
  }

  public function publisherTitle(string $slug): string {
    return $this->publishing->publisherBySlug($slug)?->displayName() ?? $slug;
  }

  public function aboutSigning(): array {
    $keys = $this->keys->get();
    return [
      '#theme' => 'tgm_about_signing',
      '#marketplace' => $this->settings->marketplaceId(),
      '#keys' => $keys['keys'] ?? [],
      '#keys_url' => Url::fromRoute('toolgate_marketplace.keys')->toString(),
      '#cache' => ['max-age' => 300],
    ];
  }

  public function terms(): array {
    return ['#theme' => 'tgm_static', '#sections' => [
      ['title' => $this->t('What this site is'), 'text' => $this->t('OLO Orgs lists ToolGate tool packages that publishers upload. Every package is checked for format and signed before it is listed.')],
      ['title' => $this->t('What signing means'), 'text' => $this->t('A signature says the package was checked and published here under the named publisher. It is not a code review or a security audit. Review a tool before you deploy it.')],
      ['title' => $this->t('Publisher responsibility'), 'text' => $this->t('Publishers are responsible for what they upload and for the information on their profile. Packages that break the law or harm users are removed.')],
    ]];
  }

  public function privacy(): array {
    return ['#theme' => 'tgm_static', '#sections' => [
      ['title' => $this->t('What is public'), 'text' => $this->t('Your publisher profile fields and your published packages. Your login email is never shown; a public contact email appears only if you add one.')],
      ['title' => $this->t('What is private'), 'text' => $this->t('Your account email and password, rejected or unfinished uploads, and your account roles.')],
      ['title' => $this->t('Logs'), 'text' => $this->t('We log package ids, versions, publisher ids and validation results to run the service. Credentials and secrets are never logged.')],
    ]];
  }

  public function sitemap(): Response {
    $urls = ['/', '/tools', '/publishers'];
    $slugs = [];
    foreach ($this->catalog->latest() as $version) {
      $urls[] = '/tools/' . $version->packageId();
      $slugs[(string) $version->publisher()?->slug()] = TRUE;
    }
    foreach (array_keys(array_filter($slugs)) as $slug) {
      $urls[] = '/publishers/' . $slug;
    }
    $xml = '<?xml version="1.0" encoding="UTF-8"?>' . "\n" . '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' . "\n";
    foreach ($urls as $path) {
      $xml .= '  <url><loc>' . htmlspecialchars($this->catalog->url($path), ENT_XML1) . "</loc></url>\n";
    }
    $xml .= "</urlset>\n";
    return new Response($xml, 200, ['Content-Type' => 'application/xml; charset=utf-8', 'Cache-Control' => 'public, max-age=3600']);
  }

  public function myPackages(): array {
    $publisher = $this->publishing->publisherFor($this->currentUser);
    if (!$publisher) {
      return [
        '#markup' => '<p>' . $this->t('Create your publisher profile to start publishing.') . '</p>',
        'link' => ['#type' => 'link', '#title' => $this->t('Create publisher profile'), '#url' => Url::fromRoute('toolgate_marketplace.my_profile'), '#attributes' => ['class' => ['button', 'button--primary']]],
        '#cache' => ['contexts' => ['user'], 'tags' => ['tgm_publisher_list']],
      ];
    }
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $versions = $storage->loadMultiple($storage->getQuery()->accessCheck(FALSE)->condition('uid', $this->currentUser->id())->sort('created', 'DESC')->execute());
    $rows = [];
    foreach ($versions as $version) {
      $status = $version->status();
      $actions = [];
      $add = function (string $title, string $route) use (&$actions, $version) {
        $actions[] = ['title' => $title, 'url' => Url::fromRoute($route, ['tgm_version' => $version->id()])];
      };
      match ($status) {
        Version::VALIDATED => [$add((string) $this->t('Continue'), 'toolgate_marketplace.publish_describe'), $add((string) $this->t('Discard'), 'toolgate_marketplace.version_discard')],
        Version::REJECTED => [$add((string) $this->t('See why'), 'toolgate_marketplace.publish_checks'), $add((string) $this->t('Discard'), 'toolgate_marketplace.version_discard')],
        Version::AVAILABLE => [$add((string) $this->t('Edit listing'), 'toolgate_marketplace.version_edit'), $add((string) $this->t('Delist'), 'toolgate_marketplace.version_delist')],
        default => NULL,
      };
      $name = $version->packageId()
        ? ['data' => ['#type' => 'link', '#title' => $version->packageId(), '#url' => Url::fromRoute('toolgate_marketplace.tool', ['package_id' => $version->packageId()])]]
        : $this->t('Rejected upload');
      $rows[] = [
        $name,
        $version->version() ?? '',
        ['data' => ['#markup' => '<span class="tgm-chip tgm-chip--' . strtolower($status) . '">' . self::statusLabel($status) . '</span>']],
        $this->dateFormatter->format((int) $version->get('changed')->value, 'short'),
        ['data' => ['#type' => 'operations', '#links' => $actions]],
      ];
    }
    return [
      'actions' => [
        '#type' => 'container',
        '#attributes' => ['class' => ['tgm-actions']],
        'upload' => ['#type' => 'link', '#title' => $this->t('Publish a tool'), '#url' => Url::fromRoute('toolgate_marketplace.publish'), '#attributes' => ['class' => ['button', 'button--primary']]],
        'profile' => ['#type' => 'link', '#title' => $this->t('Edit publisher profile'), '#url' => Url::fromRoute('toolgate_marketplace.my_profile'), '#attributes' => ['class' => ['button']]],
        'public' => ['#type' => 'link', '#title' => $this->t('View public profile'), '#url' => Url::fromRoute('toolgate_marketplace.publisher', ['slug' => $publisher->slug()]), '#attributes' => ['class' => ['button']]],
      ],
      'table' => [
        '#type' => 'table',
        '#header' => [$this->t('Package'), $this->t('Version'), $this->t('Status'), $this->t('Updated'), $this->t('Actions')],
        '#rows' => $rows,
        '#empty' => $this->t('You have not uploaded a package yet.'),
      ],
      '#cache' => ['contexts' => ['user'], 'tags' => ['tgm_version_list', 'tgm_publisher_list']],
    ];
  }

  /**
   * Step 2 of the publish wizard: the live /inspect result.
   */
  public function checks(Version $tgm_version): array|RedirectResponse {
    $inspect = $tgm_version->json('inspect');
    $valid = $tgm_version->status() === Version::VALIDATED;
    if (in_array($tgm_version->status(), [Version::AVAILABLE, Version::DELISTED], TRUE)) {
      return new RedirectResponse(Url::fromRoute('toolgate_marketplace.tool_version', ['package_id' => $tgm_version->packageId(), 'version' => $tgm_version->version()])->toString());
    }
    return [
      'steps' => ['#theme' => 'tgm_steps', '#current' => $valid ? 3 : 2],
      'checks' => [
        '#theme' => 'tgm_checks',
        '#valid' => $valid,
        '#version' => [
          'packageId' => $inspect['packageId'] ?? NULL,
          'version' => $inspect['version'] ?? NULL,
          'runtime' => $inspect['runtime'] ?? NULL,
          'packageDigest' => $inspect['packageDigest'] ?? NULL,
          'apiChange' => $inspect['apiChange'] ?? NULL,
          'size' => (string) ByteSizeMarkup::create((int) $tgm_version->get('size_bytes')->value),
        ],
        '#errors' => $tgm_version->json('errors') ?: ($inspect['errors'] ?? []),
        '#warnings' => $inspect['warnings'] ?? [],
        '#api_changes' => $inspect['apiChanges'] ?? [],
        '#tools' => $inspect['tools'] ?? [],
        '#settings' => $inspect['requiredSettings'] ?? [],
        '#compat' => self::compatHint($inspect['compatibility'] ?? NULL),
        '#next_url' => $valid ? Url::fromRoute('toolgate_marketplace.publish_describe', ['tgm_version' => $tgm_version->id()])->toString() : Url::fromRoute('toolgate_marketplace.publish')->toString(),
        '#discard_url' => Url::fromRoute('toolgate_marketplace.version_discard', ['tgm_version' => $tgm_version->id()])->toString(),
      ],
      '#cache' => ['max-age' => 0],
    ];
  }

  /**
   * A tool card render array from a version.
   */
  public function card(Version $version): array {
    $item = $this->catalog->catalogItem($version);
    return [
      '#theme' => 'tgm_card',
      '#item' => $item,
      '#compat' => self::compatHint($item['compatibility']),
      '#avatar' => $this->avatarUrl($item['publisher']),
      '#url' => Url::fromRoute('toolgate_marketplace.tool', ['package_id' => $item['packageId']])->toString(),
    ];
  }

  /**
   * "Tool Host ≥ 0.10, Java 17" from a compatibility block.
   */
  public static function compatHint(?array $compat): string {
    if (!$compat) {
      return '';
    }
    $parts = [];
    if (!empty($compat['minimumToolHostVersion'])) {
      $parts[] = 'Tool Host ≥ ' . preg_replace('/\.0$/', '', (string) $compat['minimumToolHostVersion']);
    }
    foreach ($compat['runtimes'] ?? [] as $runtime) {
      $parts[] = ucfirst((string) ($runtime['language'] ?? '')) . (isset($runtime['version']) ? ' ' . $runtime['version'] : '');
    }
    if (isset($compat['protocol']['min'], $compat['protocol']['max'])) {
      $parts[] = $compat['protocol']['min'] === $compat['protocol']['max'] ? 'protocol ' . $compat['protocol']['min'] : 'protocol ' . $compat['protocol']['min'] . '–' . $compat['protocol']['max'];
    }
    return implode(', ', array_filter($parts));
  }

  public static function statusLabel(string $status): string {
    return match ($status) {
      Version::VALIDATED => 'Validated',
      Version::AVAILABLE => 'Available',
      Version::DELISTED => 'Delisted',
      Version::REJECTED => 'Rejected',
      default => 'Checking',
    };
  }

  private function avatarUrl(?array $publisher): ?string {
    return !empty($publisher['avatarMediaId']) ? Url::fromRoute('toolgate_marketplace.api.media', ['media_id' => $publisher['avatarMediaId']])->toString() : NULL;
  }

  private function listCache(array $build): array {
    $metadata = CacheableMetadata::createFromRenderArray($build)
      ->addCacheTags(['tgm_version_list', 'tgm_publisher_list', 'tgm_package_list']);
    $metadata->applyTo($build);
    return $build;
  }

}
