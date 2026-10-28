<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical LocalRuntimeKind wire values. */
enum LocalRuntimeKind: string {
    case JAVA_SDK = 'JAVA_SDK';
    case PYTHON_SDK = 'PYTHON_SDK';
}
