<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerConsumerKind wire values. */
enum TriggerConsumerKind: string {
    case SUBSCRIPTION = 'SUBSCRIPTION';
    case ACTION = 'ACTION';
    case TEST = 'TEST';
}
