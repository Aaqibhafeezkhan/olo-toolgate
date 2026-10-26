<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical InsightsSpanName wire values. */
enum InsightsSpanName: string {
    case GATEWAY_REQUEST = 'GATEWAY_REQUEST';
    case GATEWAY_AUTHORIZE = 'GATEWAY_AUTHORIZE';
    case GATEWAY_DISPATCH = 'GATEWAY_DISPATCH';
    case HOST_ADMISSION = 'HOST_ADMISSION';
    case HOST_EXECUTE = 'HOST_EXECUTE';
    case HOST_EGRESS = 'HOST_EGRESS';
    case CLIENT_EXECUTE = 'CLIENT_EXECUTE';
}
