<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerDeliveryState wire values. */
enum TriggerDeliveryState: string {
    case Pending = 'Pending';
    case InFlight = 'InFlight';
    case Delivered = 'Delivered';
    case RetryWait = 'RetryWait';
    case Held = 'Held';
    case Superseded = 'Superseded';
    case DeadLettered = 'DeadLettered';
    case Skipped = 'Skipped';
}
