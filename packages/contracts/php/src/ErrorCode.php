<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ErrorCode wire values. */
enum ErrorCode: string {
    case VALIDATION = 'VALIDATION';
    case UNAUTHORIZED = 'UNAUTHORIZED';
    case FORBIDDEN = 'FORBIDDEN';
    case NOT_FOUND = 'NOT_FOUND';
    case CONFLICT = 'CONFLICT';
    case DEPENDENCY_UNAVAILABLE = 'DEPENDENCY_UNAVAILABLE';
    case TIMEOUT = 'TIMEOUT';
    case INTERNAL = 'INTERNAL';
    case UNSUPPORTED = 'UNSUPPORTED';
    case TENANT_MISMATCH = 'TENANT_MISMATCH';
    case TENANT_UNAVAILABLE = 'TENANT_UNAVAILABLE';
    case MISDIRECTED_REQUEST = 'MISDIRECTED_REQUEST';
}
