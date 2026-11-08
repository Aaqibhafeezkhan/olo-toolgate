<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical EnterpriseExecutionCoverage wire values. */
enum EnterpriseExecutionCoverage: string {
    case NO_AUTHORITY_CONSUMED = 'NO_AUTHORITY_CONSUMED';
    case MEDIATED_ONLY = 'MEDIATED_ONLY';
    case UNMEDIATED_POSSIBLE = 'UNMEDIATED_POSSIBLE';
}
