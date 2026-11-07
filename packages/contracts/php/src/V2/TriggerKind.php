<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerKind wire values. */
enum TriggerKind: string {
    case SERVER_TRIGGER = 'SERVER_TRIGGER';
    case CLIENT_TRIGGER = 'CLIENT_TRIGGER';
}
