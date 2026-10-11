<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ReservationOutcome wire values. */
enum ReservationOutcome: string {
    case NEW = 'NEW';
    case EXISTING = 'EXISTING';
    case IDEMPOTENCY_CONFLICT = 'IDEMPOTENCY_CONFLICT';
    case IDEMPOTENCY_KEY_IN_USE = 'IDEMPOTENCY_KEY_IN_USE';
    case EXPIRED_KEY = 'EXPIRED_KEY';
}
