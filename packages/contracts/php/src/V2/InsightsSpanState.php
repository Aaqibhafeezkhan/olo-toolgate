<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical InsightsSpanState wire values. */
enum InsightsSpanState: string {
    case NOT_COLLECTED = 'NOT_COLLECTED';
    case PENDING = 'PENDING';
    case RETAINED = 'RETAINED';
    case SAMPLED_OUT = 'SAMPLED_OUT';
    case DROPPED = 'DROPPED';
}
