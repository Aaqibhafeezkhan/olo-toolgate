<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A snapshot tool: the upstream definition and its definition digest (tool-sdk/conformance/mcp-proxy). */
final readonly class McpToolDefinition implements \JsonSerializable {
    public function __construct(
        public McpUpstreamTool $tool,
        public string $definitionDigest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tool', 'definitionDigest']) || array_diff(['tool', 'definitionDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            McpUpstreamTool::fromArray($data['tool']),
            $data['definitionDigest']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
