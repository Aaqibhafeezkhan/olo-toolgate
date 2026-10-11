<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical PackageKind wire values. */
enum PackageKind: string {
    case SDK = 'SDK';
    case CONTAINER = 'CONTAINER';
    case OPENAPI = 'OPENAPI';
    case MCP_PROXY = 'MCP_PROXY';
}
