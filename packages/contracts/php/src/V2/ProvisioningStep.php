<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ProvisioningStep wire values. */
enum ProvisioningStep: string {
    case VALIDATE = 'VALIDATE';
    case IMPORT = 'IMPORT';
    case DEPLOY = 'DEPLOY';
    case RELEASE = 'RELEASE';
    case ROLLOUT = 'ROLLOUT';
    case ACTIVATE = 'ACTIVATE';
    case DONE = 'DONE';
}
