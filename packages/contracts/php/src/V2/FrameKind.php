<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical FrameKind wire values. */
enum FrameKind: string {
    case RESULT = 'RESULT';
    case PROGRESS = 'PROGRESS';
    case INPUT_REQUEST = 'INPUT_REQUEST';
    case LOG = 'LOG';
}
