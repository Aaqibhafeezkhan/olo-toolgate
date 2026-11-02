<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseEffectGenerationState wire values. */
enum EnterpriseEffectGenerationState: string {
    case AUTHORIZED = 'AUTHORIZED';
    case RELEASED = 'RELEASED';
    case SENDING = 'SENDING';
    case CONSUMED = 'CONSUMED';
    case AMBIGUOUS = 'AMBIGUOUS';
    case COMPLETED = 'COMPLETED';
    case FAILED = 'FAILED';
    case CONFLICT = 'CONFLICT';
}
