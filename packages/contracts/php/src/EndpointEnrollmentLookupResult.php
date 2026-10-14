<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** The pending enrollment a valid code names, for comparison with the client before approval. */
final readonly class EndpointEnrollmentLookupResult implements \JsonSerializable {
    public function __construct(
        public string $enrollmentId,
        public string $deviceId,
        public ClientPlatform $platform,
        public string $keyFingerprint,
        public int $expiresAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['enrollmentId', 'deviceId', 'platform', 'keyFingerprint', 'expiresAtUnixMs']) || array_diff(['enrollmentId', 'deviceId', 'platform', 'keyFingerprint', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['enrollmentId'],
            $data['deviceId'],
            ClientPlatform::from($data['platform']),
            $data['keyFingerprint'],
            $data['expiresAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
