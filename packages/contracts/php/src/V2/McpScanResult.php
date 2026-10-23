<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** What the scanning host observed, reported once by the lease holder before the deadline. */
final readonly class McpScanResult implements \JsonSerializable {
    public function __construct(
        public string $scanId,
        public string $fencingToken,
        public McpServerInfo $serverInfo,
        public string $protocolVersion,
        public string $spkiPin,
        public array $tools
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['scanId', 'fencingToken', 'serverInfo', 'protocolVersion', 'spkiPin', 'tools']) || array_diff(['scanId', 'fencingToken', 'serverInfo', 'protocolVersion', 'spkiPin', 'tools'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['scanId'],
            $data['fencingToken'],
            McpServerInfo::fromArray($data['serverInfo']),
            $data['protocolVersion'],
            $data['spkiPin'],
            array_map(static fn ($item) => McpUpstreamTool::fromArray($item), $data['tools'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
