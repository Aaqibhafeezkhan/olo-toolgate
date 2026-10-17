<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Device-key signature over the domain-separated console challenge nonce. */
final readonly class ConsoleDeviceProof implements \JsonSerializable {
    public function __construct(
        public string $nonce,
        public string $deviceId,
        public string $keyFingerprint,
        public string $signature
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['nonce', 'deviceId', 'keyFingerprint', 'signature']) || array_diff(['nonce', 'deviceId', 'keyFingerprint', 'signature'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['nonce'],
            $data['deviceId'],
            $data['keyFingerprint'],
            $data['signature']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
