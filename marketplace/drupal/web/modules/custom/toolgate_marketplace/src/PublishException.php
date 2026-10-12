<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

/**
 * A publishing step refused with a message the publisher can act on.
 */
final class PublishException extends \RuntimeException {

  public function __construct(
    public readonly string $errorCode,
    string $message,
    public readonly bool $retryable = FALSE,
  ) {
    parent::__construct($message);
  }

}
