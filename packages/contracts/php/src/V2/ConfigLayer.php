<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ConfigLayer wire values. */
enum ConfigLayer: string {
    case PLATFORM_CEILING = 'PLATFORM_CEILING';
    case ENVIRONMENT = 'ENVIRONMENT';
    case TENANT_POLICY = 'TENANT_POLICY';
    case POOL_OR_DEVICE_GROUP = 'POOL_OR_DEVICE_GROUP';
    case DEPLOYMENT_BINDING = 'DEPLOYMENT_BINDING';
    case PACKAGE_DEFAULT = 'PACKAGE_DEFAULT';
}
