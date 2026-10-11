<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool Host or device consumes the single-use nonce; returns the fencing token. */
final readonly class PermitConsumption implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public SignedInvocationPermit $permit,
        public string $argumentsDigest,
        public string $toolDescriptorDigest,
        public string $packageDigest,
        public string $artifactDigest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'permit', 'argumentsDigest', 'toolDescriptorDigest', 'packageDigest', 'artifactDigest']) || array_diff(['invocationId', 'permit', 'argumentsDigest', 'toolDescriptorDigest', 'packageDigest', 'artifactDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            SignedInvocationPermit::fromArray($data['permit']),
            $data['argumentsDigest'],
            $data['toolDescriptorDigest'],
            $data['packageDigest'],
            $data['artifactDigest']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
