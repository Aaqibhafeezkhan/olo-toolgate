<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Controller;

use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\DependencyInjection\ContainerInjectionInterface;
use Drupal\toolgate_marketplace\Health;
use Symfony\Component\HttpFoundation\JsonResponse;

/**
 * GET /health for the proxy, Kubernetes probes and CI (prompt §6.2).
 *
 * No session, no page rendering. 503 when this replica can't reach the
 * database, the shared cache or object storage. The seed status is
 * reported but never fails the probe: a degraded seed is shown, not hidden.
 */
final class HealthController implements ContainerInjectionInterface {

  use AutowireTrait;

  public function __construct(private readonly Health $health) {}

  public function health(): JsonResponse {
    $result = $this->health->check();
    $response = new JsonResponse($result, $result['status'] === 'ok' ? 200 : 503);
    $response->headers->set('Cache-Control', 'no-store');
    return $response;
  }

}
