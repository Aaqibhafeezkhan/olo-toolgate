<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical CredentialKind wire values. */
enum CredentialKind: string {
    case STATIC_TOKEN = 'STATIC_TOKEN';
    case JWT = 'JWT';
    case INTROSPECTION = 'INTROSPECTION';
}
