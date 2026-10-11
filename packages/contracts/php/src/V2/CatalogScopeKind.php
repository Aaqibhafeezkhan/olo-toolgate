<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Canonical CatalogScopeKind wire values. */
enum CatalogScopeKind: string {
    case ALL = 'ALL';
    case AGENT = 'AGENT';
    case AGENT_GROUP = 'AGENT_GROUP';
    case AGENT_IN_GROUP = 'AGENT_IN_GROUP';
}
