<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Authenticated target evidence, accepted after grant expiry without renewing authority. */
final readonly class ServicePermitTargetOutcome implements \JsonSerializable {
    public function __construct(
        public ServicePermitClaimReference $claim,
        public ServicePermitTargetOutcomeKind $outcome,
        public string $resultDigest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['claim', 'outcome', 'resultDigest']) || array_diff(['claim', 'outcome', 'resultDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ServicePermitClaimReference::fromArray($data['claim']),
            ServicePermitTargetOutcomeKind::from($data['outcome']),
            $data['resultDigest']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
