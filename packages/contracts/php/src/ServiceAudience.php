<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ServiceAudience wire values. */
enum ServiceAudience: string {
    case olo_memory = 'olo-memory';
    case olo_rag = 'olo-rag';
}
