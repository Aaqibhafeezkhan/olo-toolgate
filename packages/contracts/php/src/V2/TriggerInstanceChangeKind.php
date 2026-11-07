<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerInstanceChangeKind wire values. */
enum TriggerInstanceChangeKind: string {
    case Added = 'Added';
    case Updated = 'Updated';
    case Removed = 'Removed';
    case Unchanged = 'Unchanged';
}
