<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerOverflow wire values. */
enum TriggerOverflow: string {
    case BLOCK = 'BLOCK';
    case DROP_OLDEST = 'DROP_OLDEST';
    case DROP_NEWEST = 'DROP_NEWEST';
}
