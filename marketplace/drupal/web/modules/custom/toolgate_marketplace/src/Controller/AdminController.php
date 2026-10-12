<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Controller;

use Drupal\Core\Datetime\DateFormatterInterface;
use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\DependencyInjection\ContainerInjectionInterface;
use Drupal\Core\Entity\EntityTypeManagerInterface;
use Drupal\Core\StringTranslation\StringTranslationTrait;
use Drupal\Core\Url;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\toolgate_marketplace\SeedRunner;

/**
 * Admin dashboard: every version, takedowns and the seed status.
 */
final class AdminController implements ContainerInjectionInterface {

  use AutowireTrait;
  use StringTranslationTrait;

  public function __construct(
    private readonly EntityTypeManagerInterface $entityTypeManager,
    private readonly SeedRunner $seed,
    private readonly DateFormatterInterface $dateFormatter,
  ) {}

  public function dashboard(): array {
    $storage = $this->entityTypeManager->getStorage('tgm_version');
    $ids = $storage->getQuery()->accessCheck(FALSE)->sort('changed', 'DESC')->pager(50)->execute();
    $rows = [];
    foreach ($storage->loadMultiple($ids) as $version) {
      $links = [];
      if ($version->status() === Version::AVAILABLE) {
        $links[] = ['title' => $this->t('Take down'), 'url' => Url::fromRoute('toolgate_marketplace.version_delist', ['tgm_version' => $version->id()])];
      }
      if ($version->packageId() && in_array($version->status(), [Version::AVAILABLE, Version::DELISTED], TRUE)) {
        $links[] = ['title' => $this->t('View'), 'url' => Url::fromRoute('toolgate_marketplace.tool_version', ['package_id' => $version->packageId(), 'version' => $version->version()])];
      }
      $rows[] = [
        $version->packageId() ?? $this->t('(rejected upload)'),
        $version->version() ?? '',
        $version->publisher()?->slug() ?? '',
        ['data' => ['#markup' => '<span class="tgm-chip tgm-chip--' . strtolower($version->status()) . '">' . PageController::statusLabel($version->status()) . '</span>']],
        $this->dateFormatter->format((int) $version->get('changed')->value, 'short'),
        ['data' => ['#type' => 'operations', '#links' => $links]],
      ];
    }
    return [
      'seed' => $this->banner(),
      'table' => [
        '#type' => 'table',
        '#header' => [$this->t('Package'), $this->t('Version'), $this->t('Publisher'), $this->t('Status'), $this->t('Updated'), $this->t('Actions')],
        '#rows' => $rows,
        '#empty' => $this->t('No uploads yet.'),
      ],
      'pager' => ['#type' => 'pager'],
      '#cache' => ['max-age' => 0],
    ];
  }

  public function seed(): array {
    return ['#theme' => 'tgm_seed_status', '#status' => $this->seed->status(), '#cache' => ['max-age' => 0]];
  }

  private function banner(): array {
    $status = $this->seed->status();
    if (($status['seed'] ?? NULL) === 'complete') {
      return [];
    }
    $message = $status === NULL
      ? $this->t('The example seed has not run yet.')
      : $this->t('The example seed is degraded: @count expected packages are missing.', ['@count' => count($status['missing'])]);
    return [
      '#type' => 'container',
      '#attributes' => ['class' => ['messages', 'messages--warning'], 'role' => 'status'],
      'text' => ['#markup' => $message . ' '],
      'link' => ['#type' => 'link', '#title' => $this->t('See the seed status'), '#url' => Url::fromRoute('toolgate_marketplace.admin_seed')],
    ];
  }

}
