<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical WorkflowJsonNodeKind wire values. */
enum WorkflowJsonNodeKind: string {
    case value = 'value';
    case agent = 'agent';
    case model = 'model';
    case tool = 'tool';
    case branch = 'branch';
}
