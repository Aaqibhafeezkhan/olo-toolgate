<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical ApiChange wire values. */
enum ApiChange: string {
    case NONE = 'NONE';
    case PATCH = 'PATCH';
    case MINOR = 'MINOR';
    case MAJOR = 'MAJOR';
}
