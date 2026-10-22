<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** mcp-proxy.json, the one artifact of an MCP_PROXY package (media type application/vnd.ololabs.toolgate.mcp-proxy+json). */
final readonly class McpProxyDocument implements \JsonSerializable {
    public function __construct(
        public int $formatVersion,
        public McpProxyServer $server,
        public int $approvalSequence,
        public bool $strict,
        public array $tools
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['formatVersion', 'server', 'approvalSequence', 'strict', 'tools']) || array_diff(['formatVersion', 'server', 'approvalSequence', 'strict', 'tools'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['formatVersion'] !== 1) {
            throw new \InvalidArgumentException('formatVersion must be 1');
        }
        return new self(
            $data['formatVersion'],
            McpProxyServer::fromArray($data['server']),
            $data['approvalSequence'],
            $data['strict'],
            array_map(static fn ($item) => McpApprovedTool::fromArray($item), $data['tools'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
