<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseOutcomeGrade wire values. */
enum EnterpriseOutcomeGrade: string {
    case TARGET_CONFIRMED = 'TARGET_CONFIRMED';
    case BROKER_OBSERVED = 'BROKER_OBSERVED';
    case HUMAN_RECONCILED = 'HUMAN_RECONCILED';
    case CONTROL_PROVEN = 'CONTROL_PROVEN';
    case EXECUTOR_REPORTED = 'EXECUTOR_REPORTED';
    case UNKNOWN = 'UNKNOWN';
}
