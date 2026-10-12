<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

/**
 * A failed helper call.
 *
 * $retryable is TRUE when the helper was unreachable, busy or failed on its
 * side: the upload can be tried again and nothing is published (design §11).
 */
final class HelperException extends \RuntimeException {

  public function __construct(
    public readonly string $errorCode,
    string $message,
    public readonly bool $retryable,
  ) {
    parent::__construct($message);
  }

}
