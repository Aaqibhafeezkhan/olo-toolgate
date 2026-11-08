<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerSourceReplay wire values. */
enum TriggerSourceReplay: string {
    case EVENT_LOG = 'EVENT_LOG';
    case STATE_SNAPSHOT = 'STATE_SNAPSHOT';
    case NON_REPLAYABLE = 'NON_REPLAYABLE';
}
