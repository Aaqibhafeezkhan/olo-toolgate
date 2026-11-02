<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Issued authority and conservative monotonic send budget, measured from before the issuance request. */
final readonly class EffectGrant implements \JsonSerializable {
    public function __construct(
        public SignedEffectGrant $grant,
        public int $validForMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['grant', 'validForMs']) || array_diff(['grant', 'validForMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            SignedEffectGrant::fromArray($data['grant']),
            $data['validForMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
