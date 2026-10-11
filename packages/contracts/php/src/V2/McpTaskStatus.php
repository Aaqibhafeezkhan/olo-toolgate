<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical McpTaskStatus wire values. */
enum McpTaskStatus: string {
    case working = 'working';
    case input_required = 'input_required';
    case completed = 'completed';
    case failed = 'failed';
    case cancelled = 'cancelled';
}
