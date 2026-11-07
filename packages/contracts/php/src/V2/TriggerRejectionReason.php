<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical TriggerRejectionReason wire values. */
enum TriggerRejectionReason: string {
    case UnknownInstance = 'UnknownInstance';
    case NotAssigned = 'NotAssigned';
    case StaleLeaseEpoch = 'StaleLeaseEpoch';
    case StaleConfigRevision = 'StaleConfigRevision';
}
