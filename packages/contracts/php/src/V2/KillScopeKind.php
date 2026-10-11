<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical KillScopeKind wire values. */
enum KillScopeKind: string {
    case PACKAGE = 'PACKAGE';
    case PACKAGE_DIGEST = 'PACKAGE_DIGEST';
    case PUBLISHER = 'PUBLISHER';
    case TOOL = 'TOOL';
    case POOL = 'POOL';
    case RUNTIME_IMAGE = 'RUNTIME_IMAGE';
    case TENANT = 'TENANT';
}
