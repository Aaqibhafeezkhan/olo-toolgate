<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** A short-lived server challenge bound to a verified console session and device key. */
final readonly class ConsoleDeviceChallenge implements \JsonSerializable {
    public function __construct(
        public string $nonce,
        public string $deviceId,
        public string $keyFingerprint,
        public int $expiresAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['nonce', 'deviceId', 'keyFingerprint', 'expiresAtUnixMs']) || array_diff(['nonce', 'deviceId', 'keyFingerprint', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['nonce'],
            $data['deviceId'],
            $data['keyFingerprint'],
            $data['expiresAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
