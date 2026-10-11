<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical RefusalReason wire values. */
enum RefusalReason: string {
    case BUSY = 'BUSY';
    case DIGEST_NOT_PRESENT = 'DIGEST_NOT_PRESENT';
    case STALE_SEQUENCE = 'STALE_SEQUENCE';
    case PERMIT_INVALID = 'PERMIT_INVALID';
    case PERMIT_EXPIRED = 'PERMIT_EXPIRED';
    case STORE_UNAVAILABLE = 'STORE_UNAVAILABLE';
    case AUDIT_UNAVAILABLE = 'AUDIT_UNAVAILABLE';
    case UNSUPPORTED_TIER = 'UNSUPPORTED_TIER';
    case KILLED = 'KILLED';
}
