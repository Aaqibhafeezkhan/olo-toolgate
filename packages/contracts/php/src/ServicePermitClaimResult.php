<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Status only. Never grants execution authority; the target must own its shared local claim. */
final readonly class ServicePermitClaimResult implements \JsonSerializable {
    public function __construct(
        public string $effectId,
        public int $authorizationGeneration,
        public ServicePermitClaimState $status
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['effectId', 'authorizationGeneration', 'status']) || array_diff(['effectId', 'authorizationGeneration', 'status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['effectId'],
            $data['authorizationGeneration'],
            ServicePermitClaimState::from($data['status'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
