<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical WorkflowProgressKind wire values. */
enum WorkflowProgressKind: string {
    case NODE_COMPLETED = 'NODE_COMPLETED';
    case AGENT_STARTED = 'AGENT_STARTED';
    case AGENT_COMPLETED = 'AGENT_COMPLETED';
    case MODEL_STARTED = 'MODEL_STARTED';
    case MODEL_COMPLETED = 'MODEL_COMPLETED';
    case TOOL_STARTED = 'TOOL_STARTED';
    case TOOL_COMPLETED = 'TOOL_COMPLETED';
    case TOOL_FAILED = 'TOOL_FAILED';
}
