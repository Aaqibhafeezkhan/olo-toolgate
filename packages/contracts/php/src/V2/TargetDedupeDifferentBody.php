<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TargetDedupeDifferentBody wire values. */
enum TargetDedupeDifferentBody: string {
    case REJECT = 'REJECT';
    case REPLAY_FIRST = 'REPLAY_FIRST';
}
