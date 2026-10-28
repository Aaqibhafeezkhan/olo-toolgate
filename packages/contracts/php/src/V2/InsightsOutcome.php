<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical InsightsOutcome wire values. */
enum InsightsOutcome: string {
    case PENDING_APPROVAL = 'PENDING_APPROVAL';
    case IN_PROGRESS = 'IN_PROGRESS';
    case SUCCEEDED = 'SUCCEEDED';
    case FAILED = 'FAILED';
    case PARTIAL = 'PARTIAL';
    case CANCELLED = 'CANCELLED';
    case EXPIRED = 'EXPIRED';
    case OUTCOME_UNKNOWN = 'OUTCOME_UNKNOWN';
}
