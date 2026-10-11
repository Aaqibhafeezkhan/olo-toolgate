<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TargetKind wire values. */
enum TargetKind: string {
    case TOOL_HOST_POOL = 'TOOL_HOST_POOL';
    case DEVICE_GROUP = 'DEVICE_GROUP';
}
