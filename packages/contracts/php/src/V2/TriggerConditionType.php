<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerConditionType wire values. */
enum TriggerConditionType: string {
    case Installed = 'Installed';
    case Leased = 'Leased';
    case Ready = 'Ready';
    case Degraded = 'Degraded';
    case Reconciled = 'Reconciled';
    case Stale = 'Stale';
    case Unexpected = 'Unexpected';
}
