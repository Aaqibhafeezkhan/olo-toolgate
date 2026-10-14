<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical ControlEntityKind wire values. */
enum ControlEntityKind: string {
    case USER = 'USER';
    case TEAM = 'TEAM';
    case AGENT = 'AGENT';
    case TOOL = 'TOOL';
    case POLICY = 'POLICY';
    case DEVICE = 'DEVICE';
    case ROLE = 'ROLE';
    case DEVICE_GROUP = 'DEVICE_GROUP';
    case AGENT_GROUP = 'AGENT_GROUP';
    case TOOL_GROUP = 'TOOL_GROUP';
    case GRANT = 'GRANT';
    case DELEGATION = 'DELEGATION';
    case AGENT_DELEGATION = 'AGENT_DELEGATION';
    case BINDING = 'BINDING';
    case EXTRACTOR = 'EXTRACTOR';
    case WORKLOAD_BINDING = 'WORKLOAD_BINDING';
    case IDENTITY_BINDING = 'IDENTITY_BINDING';
    case DEVICE_EVIDENCE = 'DEVICE_EVIDENCE';
    case SETTINGS = 'SETTINGS';
}
