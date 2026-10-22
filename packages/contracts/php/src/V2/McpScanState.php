<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical McpScanState wire values. */
enum McpScanState: string {
    case QUEUED = 'QUEUED';
    case CLAIMED = 'CLAIMED';
    case ADMITTED = 'ADMITTED';
    case FINISHED = 'FINISHED';
    case FAILED = 'FAILED';
    case EXPIRED = 'EXPIRED';
}
