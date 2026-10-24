<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ReconciliationMethod wire values. */
enum ReconciliationMethod: string {
    case DOWNSTREAM_IDEMPOTENCY = 'DOWNSTREAM_IDEMPOTENCY';
    case STATUS_LOOKUP = 'STATUS_LOOKUP';
    case MANUAL = 'MANUAL';
}
