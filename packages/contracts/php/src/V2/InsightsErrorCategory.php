<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical InsightsErrorCategory wire values. */
enum InsightsErrorCategory: string {
    case POLICY = 'POLICY';
    case TIMEOUT = 'TIMEOUT';
    case CANCELLED = 'CANCELLED';
    case TOOL_ERROR = 'TOOL_ERROR';
    case INFRASTRUCTURE = 'INFRASTRUCTURE';
    case INVALID_REQUEST = 'INVALID_REQUEST';
    case PARTIAL = 'PARTIAL';
    case UNKNOWN = 'UNKNOWN';
}
