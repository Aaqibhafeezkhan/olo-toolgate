<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Control-created stamp for an immutable generated MCP tool policy; not accepted from approval or package authors. */
final readonly class NativeMcpEffectReview implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public McpEffectPolicy $policy,
        public string $reviewedBy,
        public int $reviewedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'policy', 'reviewedBy', 'reviewedAtUnixMs']) || array_diff(['toolId', 'policy', 'reviewedBy', 'reviewedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            McpEffectPolicy::fromArray($data['policy']),
            $data['reviewedBy'],
            $data['reviewedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
