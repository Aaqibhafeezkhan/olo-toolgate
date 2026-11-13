<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical WorkflowDispatchDisposition wire values. */
enum WorkflowDispatchDisposition: string {
    case ADMITTED = 'ADMITTED';
    case DUPLICATE = 'DUPLICATE';
    case REJECTED = 'REJECTED';
    case FILTERED = 'FILTERED';
}
