<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical RolloutStrategy wire values. */
enum RolloutStrategy: string {
    case ALL_AT_ONCE = 'ALL_AT_ONCE';
    case COHORT = 'COHORT';
}
