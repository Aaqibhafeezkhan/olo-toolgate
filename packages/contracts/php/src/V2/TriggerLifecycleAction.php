<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerLifecycleAction wire values. */
enum TriggerLifecycleAction: string {
    case Deploy = 'Deploy';
    case Run = 'Run';
    case Pause = 'Pause';
    case Stop = 'Stop';
    case Remove = 'Remove';
    case Clear = 'Clear';
}
