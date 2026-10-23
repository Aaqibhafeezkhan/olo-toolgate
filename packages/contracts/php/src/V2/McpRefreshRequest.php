<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A Tool Host saw notifications/tools/list_changed during a call it holds and asks Control for an immediate refresh of that call's MCP server. Bound to the invocation and its fencing token, like a quarantine report. */
final readonly class McpRefreshRequest implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken']) || array_diff(['invocationId', 'fencingToken'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
