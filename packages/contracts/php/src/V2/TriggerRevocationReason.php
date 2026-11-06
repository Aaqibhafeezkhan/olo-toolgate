<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerRevocationReason wire values. */
enum TriggerRevocationReason: string {
    case AssignmentStopped = 'AssignmentStopped';
    case AssignmentRemoved = 'AssignmentRemoved';
    case PackageSuspended = 'PackageSuspended';
    case KillSwitch = 'KillSwitch';
    case ExecutorDeregistered = 'ExecutorDeregistered';
    case DeviceRevoked = 'DeviceRevoked';
    case Compromised = 'Compromised';
    case Reassigned = 'Reassigned';
}
