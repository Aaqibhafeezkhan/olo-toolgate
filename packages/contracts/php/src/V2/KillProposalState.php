<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical KillProposalState wire values. */
enum KillProposalState: string {
    case PENDING = 'PENDING';
    case APPLIED = 'APPLIED';
    case REJECTED = 'REJECTED';
    case EXPIRED = 'EXPIRED';
}
