<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One approved upstream tool and the ToolGate local name it gets (mcp.{slug}/{name}). */
final readonly class McpToolApproval implements \JsonSerializable {
    public function __construct(
        public string $upstreamName,
        public string $name,
        public ?McpEffectPolicy $effectPolicy = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['upstreamName', 'name', 'effectPolicy']) || array_diff(['upstreamName', 'name'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['upstreamName'],
            $data['name'],
            array_key_exists('effectPolicy', $data) ? McpEffectPolicy::fromArray($data['effectPolicy']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
