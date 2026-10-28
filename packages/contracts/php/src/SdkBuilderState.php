<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical SdkBuilderState wire values. */
enum SdkBuilderState: string {
    case QUEUED = 'QUEUED';
    case RUNNING = 'RUNNING';
    case READY = 'READY';
    case FAILED = 'FAILED';
}
