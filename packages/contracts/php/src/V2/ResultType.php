<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ResultType wire values. */
enum ResultType: string {
    case complete = 'complete';
    case input_required = 'input_required';
    case task = 'task';
}
