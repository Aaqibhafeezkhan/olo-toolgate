<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Access;

use Drupal\Core\Access\AccessResult;
use Drupal\Core\Entity\EntityAccessControlHandler;
use Drupal\Core\Entity\EntityInterface;
use Drupal\Core\Session\AccountInterface;
use Drupal\toolgate_marketplace\Entity\Publisher;
use Drupal\toolgate_marketplace\Entity\Version;

/**
 * Ownership comes from the logged-in account only (design §5.2).
 *
 * Publishers and packages are public. A version is public once AVAILABLE or
 * DELISTED; drafts and rejected uploads are visible to their uploader and to
 * administrators only.
 */
final class MarketplaceAccessControlHandler extends EntityAccessControlHandler {

  protected function checkAccess(EntityInterface $entity, $operation, AccountInterface $account) {
    if ($account->hasPermission('administer toolgate marketplace')) {
      return AccessResult::allowed()->cachePerPermissions();
    }
    $isOwner = static fn(?int $uid) => AccessResult::allowedIf($account->isAuthenticated() && $uid === (int) $account->id())
      ->cachePerUser()
      ->addCacheableDependency($entity);

    if ($entity instanceof Publisher) {
      return match ($operation) {
        'view' => AccessResult::allowed()->addCacheableDependency($entity),
        'update' => $isOwner($entity->getOwnerId() === NULL ? NULL : (int) $entity->getOwnerId()),
        default => AccessResult::neutral(),
      };
    }
    if ($entity instanceof Version) {
      $public = in_array($entity->status(), [Version::AVAILABLE, Version::DELISTED], TRUE);
      $owner = (int) $entity->getOwnerId();
      return match ($operation) {
        'view' => $public ? AccessResult::allowed()->addCacheableDependency($entity) : $isOwner($owner),
        'update' => $entity->status() === Version::REJECTED ? AccessResult::forbidden()->addCacheableDependency($entity) : $isOwner($owner),
        'delete' => in_array($entity->status(), [Version::VALIDATED, Version::REJECTED], TRUE) ? $isOwner($owner) : AccessResult::neutral()->addCacheableDependency($entity),
        default => AccessResult::neutral(),
      };
    }
    return $operation === 'view' ? AccessResult::allowed() : AccessResult::neutral();
  }

  protected function checkCreateAccess(AccountInterface $account, array $context, $entity_bundle = NULL) {
    return AccessResult::allowedIfHasPermission($account, 'publish toolgate packages');
  }

}
