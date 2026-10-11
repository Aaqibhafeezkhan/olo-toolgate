<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TaskState wire values. */
enum TaskState: string {
    case ACCEPTED = 'ACCEPTED';
    case AWAITING_APPROVAL = 'AWAITING_APPROVAL';
    case QUEUED = 'QUEUED';
    case DISPATCHED = 'DISPATCHED';
    case RUNNING = 'RUNNING';
    case INPUT_REQUIRED = 'INPUT_REQUIRED';
    case CANCEL_REQUESTED = 'CANCEL_REQUESTED';
    case COMPLETED = 'COMPLETED';
    case FAILED = 'FAILED';
    case REJECTED = 'REJECTED';
    case EXPIRED = 'EXPIRED';
    case CANCELLED = 'CANCELLED';
    case OUTCOME_UNKNOWN = 'OUTCOME_UNKNOWN';
}
