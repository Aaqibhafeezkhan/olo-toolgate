<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical EffectSafety wire values. */
enum EffectSafety: string {
    case READ_ONLY = 'READ_ONLY';
    case IDEMPOTENT = 'IDEMPOTENT';
    case NON_IDEMPOTENT = 'NON_IDEMPOTENT';
    case UNKNOWN = 'UNKNOWN';
}
