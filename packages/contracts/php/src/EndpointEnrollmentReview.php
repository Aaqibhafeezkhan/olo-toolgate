<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Endpoint identity foundation wire model. userCode and keyFingerprint are returned only to callers who may approve through a group rule. */
final readonly class EndpointEnrollmentReview implements \JsonSerializable {
    public function __construct(
        public string $enrollmentId,
        public string $deviceId,
        public ClientPlatform $platform,
        public EnrollmentState $state,
        public int $expiresAtUnixMs,
        public ?string $userCode = null,
        public ?string $keyFingerprint = null,
        public ?int $connectionExpiresAtUnixMs = null,
        public ?bool $unlimitedConnection = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['enrollmentId', 'userCode', 'deviceId', 'platform', 'keyFingerprint', 'state', 'expiresAtUnixMs', 'connectionExpiresAtUnixMs', 'unlimitedConnection']) || array_diff(['enrollmentId', 'deviceId', 'platform', 'state', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['enrollmentId'],
            $data['deviceId'],
            ClientPlatform::from($data['platform']),
            EnrollmentState::from($data['state']),
            $data['expiresAtUnixMs'],
            array_key_exists('userCode', $data) ? $data['userCode'] : null,
            array_key_exists('keyFingerprint', $data) ? $data['keyFingerprint'] : null,
            array_key_exists('connectionExpiresAtUnixMs', $data) ? $data['connectionExpiresAtUnixMs'] : null,
            array_key_exists('unlimitedConnection', $data) ? $data['unlimitedConnection'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
