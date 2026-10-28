<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ToolHostDispatchState wire values. */
enum ToolHostDispatchState: string {
    case QUEUED = 'QUEUED';
    case CLAIMED = 'CLAIMED';
    case ADMITTED = 'ADMITTED';
    case REFUSED = 'REFUSED';
    case FINISHED = 'FINISHED';
    case EXPIRED = 'EXPIRED';
}
