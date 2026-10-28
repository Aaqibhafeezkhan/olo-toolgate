<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** An immutable scan result; Control recomputes every digest. */
final readonly class McpSnapshot implements \JsonSerializable {
    public function __construct(
        public int $revision,
        public string $scanId,
        public McpServerInfo $serverInfo,
        public string $protocolVersion,
        public string $spkiPin,
        public int $scannedAtUnixMs,
        public array $tools
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['revision', 'scanId', 'serverInfo', 'protocolVersion', 'spkiPin', 'scannedAtUnixMs', 'tools']) || array_diff(['revision', 'scanId', 'serverInfo', 'protocolVersion', 'spkiPin', 'scannedAtUnixMs', 'tools'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['revision'],
            $data['scanId'],
            McpServerInfo::fromArray($data['serverInfo']),
            $data['protocolVersion'],
            $data['spkiPin'],
            $data['scannedAtUnixMs'],
            array_map(static fn ($item) => McpToolDefinition::fromArray($item), $data['tools'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
