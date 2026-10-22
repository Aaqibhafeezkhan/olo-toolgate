<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical McpQuarantineReason wire values. */
enum McpQuarantineReason: string {
    case OUTPUT_INVALID = 'OUTPUT_INVALID';
    case DEFINITION_CHANGED = 'DEFINITION_CHANGED';
    case TOOL_MISSING = 'TOOL_MISSING';
    case SERVER_CHANGED = 'SERVER_CHANGED';
    case SERVER_VERSION_CHANGED = 'SERVER_VERSION_CHANGED';
    case PIN_MISMATCH = 'PIN_MISMATCH';
}
