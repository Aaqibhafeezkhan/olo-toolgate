<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseOperationState wire values. */
enum EnterpriseOperationState: string {
    case RUNNING = 'RUNNING';
    case OUTCOME_UNKNOWN = 'OUTCOME_UNKNOWN';
    case RECONCILIATION_REQUIRED = 'RECONCILIATION_REQUIRED';
    case SUCCEEDED = 'SUCCEEDED';
    case PARTIAL = 'PARTIAL';
    case FAILED = 'FAILED';
}
