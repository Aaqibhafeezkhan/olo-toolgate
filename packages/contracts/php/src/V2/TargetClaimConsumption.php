<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Authenticated target consumption of an exact accepted request, after its durable local claim commits. Replays return status only. */
final readonly class TargetClaimConsumption implements \JsonSerializable {
    public function __construct(
        public SignedEffectGrant $grant,
        public string $claimToken,
        public string $httpRequestDigest,
        public string $executorKeySha256
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['grant', 'claimToken', 'httpRequestDigest', 'executorKeySha256']) || array_diff(['grant', 'claimToken', 'httpRequestDigest', 'executorKeySha256'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            SignedEffectGrant::fromArray($data['grant']),
            $data['claimToken'],
            $data['httpRequestDigest'],
            $data['executorKeySha256']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
