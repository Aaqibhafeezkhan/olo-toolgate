<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerTerminalRejectionPolicy wire values. */
enum TriggerTerminalRejectionPolicy: string {
    case HALT = 'HALT';
    case SKIP_AND_RECORD = 'SKIP_AND_RECORD';
}
