<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A tool of an approval. */
final readonly class McpApprovedTool implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public string $upstreamName,
        public string $definitionDigest,
        public McpEffectPolicy $effectPolicy
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'upstreamName', 'definitionDigest', 'effectPolicy']) || array_diff(['toolId', 'upstreamName', 'definitionDigest', 'effectPolicy'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['upstreamName'],
            $data['definitionDigest'],
            McpEffectPolicy::fromArray($data['effectPolicy'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
