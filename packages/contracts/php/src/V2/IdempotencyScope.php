<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical IdempotencyScope wire values. */
enum IdempotencyScope: string {
    case BUSINESS_KEY = 'BUSINESS_KEY';
    case PER_INVOCATION = 'PER_INVOCATION';
}
