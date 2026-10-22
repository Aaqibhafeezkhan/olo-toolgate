<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical McpServerState wire values. */
enum McpServerState: string {
    case REGISTERED = 'REGISTERED';
    case SCANNING = 'SCANNING';
    case SCANNED = 'SCANNED';
    case APPROVED = 'APPROVED';
    case QUARANTINED = 'QUARANTINED';
}
