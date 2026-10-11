<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical RuntimeTier wire values. */
enum RuntimeTier: string {
    case OCI_SANDBOX = 'OCI_SANDBOX';
    case WASI = 'WASI';
    case HOST_NATIVE = 'HOST_NATIVE';
}
