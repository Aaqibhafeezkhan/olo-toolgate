<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical DeviceExecutionKind wire values. */
enum DeviceExecutionKind: string {
    case ENDPOINT_CLIENT = 'ENDPOINT_CLIENT';
    case TOOL_HOST_DIRECT = 'TOOL_HOST_DIRECT';
    case TOOL_HOST_PULL = 'TOOL_HOST_PULL';
}
