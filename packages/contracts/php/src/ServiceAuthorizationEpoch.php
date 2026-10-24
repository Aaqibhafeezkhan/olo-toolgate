<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Coalesced tenant revocation feed snapshot. Services fail closed if polling becomes stale. */
final readonly class ServiceAuthorizationEpoch implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public int $authorizationEpoch,
        public int $observedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'authorizationEpoch', 'observedAtUnixMs']) || array_diff(['tenantId', 'authorizationEpoch', 'observedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['authorizationEpoch'],
            $data['observedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
