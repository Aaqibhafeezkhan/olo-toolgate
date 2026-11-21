<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ApplicationWebhookState wire values. */
enum ApplicationWebhookState: string {
    case PENDING = 'PENDING';
    case ACTIVE = 'ACTIVE';
    case REVOKED = 'REVOKED';
    case FAILED = 'FAILED';
    case EXPIRED = 'EXPIRED';
}
