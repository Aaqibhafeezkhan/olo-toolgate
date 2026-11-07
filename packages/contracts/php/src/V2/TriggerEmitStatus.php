<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerEmitStatus wire values. */
enum TriggerEmitStatus: string {
    case LOCAL_DURABLE = 'LOCAL_DURABLE';
    case RETRYABLE_REJECTION = 'RETRYABLE_REJECTION';
    case TERMINAL_REJECTION = 'TERMINAL_REJECTION';
}
