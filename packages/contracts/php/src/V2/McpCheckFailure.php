<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical McpCheckFailure wire values. */
enum McpCheckFailure: string {
    case PIN_MISMATCH = 'PIN_MISMATCH';
    case TLS_ERROR = 'TLS_ERROR';
    case UPSTREAM_UNREACHABLE = 'UPSTREAM_UNREACHABLE';
    case UPSTREAM_ERROR = 'UPSTREAM_ERROR';
    case PROTOCOL_ERROR = 'PROTOCOL_ERROR';
    case SCAN_LIMIT_EXCEEDED = 'SCAN_LIMIT_EXCEEDED';
    case DEADLINE_EXCEEDED = 'DEADLINE_EXCEEDED';
    case DEFINITION_CHANGED = 'DEFINITION_CHANGED';
    case TOOL_MISSING = 'TOOL_MISSING';
    case SERVER_CHANGED = 'SERVER_CHANGED';
}
