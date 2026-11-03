<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explicit operator review for one native MCP business call. Upstream annotations do not confer effect authority. */
final readonly class McpEffectPolicy implements \JsonSerializable {
    public function __construct(
        public EffectClassification $classification,
        public bool $consentRequired,
        public bool $acknowledgedUnfencedRisk
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['classification', 'consentRequired', 'acknowledgedUnfencedRisk']) || array_diff(['classification', 'consentRequired', 'acknowledgedUnfencedRisk'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            EffectClassification::from($data['classification']),
            $data['consentRequired'],
            $data['acknowledgedUnfencedRisk']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
