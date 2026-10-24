<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Service compares these request facts before its write, then consumes the nonce online. */
final readonly class ServicePermitConsumption implements \JsonSerializable {
    public function __construct(
        public ServicePermit $permit,
        public string $operation,
        public array $resources,
        public string $requestFingerprint,
        public string $executorKeySha256
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['permit', 'operation', 'resources', 'requestFingerprint', 'executorKeySha256']) || array_diff(['permit', 'operation', 'resources', 'requestFingerprint', 'executorKeySha256'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ServicePermit::fromArray($data['permit']),
            $data['operation'],
            array_map(static fn ($item) => ResourceDescriptor::fromArray($item), $data['resources']),
            $data['requestFingerprint'],
            $data['executorKeySha256']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
