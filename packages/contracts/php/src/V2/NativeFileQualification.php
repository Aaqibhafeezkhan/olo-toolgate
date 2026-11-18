<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** ADR 075 NativeFileQualification; only protected qualified filesystem broker evidence. */
final readonly class NativeFileQualification implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public string $deviceGroupId,
        public string $imageDigest,
        public int $expiresAtUnixMs,
        public string $acknowledgedRisk
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'deviceGroupId', 'imageDigest', 'expiresAtUnixMs', 'acknowledgedRisk']) || array_diff(['deviceId', 'deviceGroupId', 'imageDigest', 'expiresAtUnixMs', 'acknowledgedRisk'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['acknowledgedRisk'] !== 'UNFENCED_FILESYSTEM') {
            throw new \InvalidArgumentException('acknowledgedRisk must be UNFENCED_FILESYSTEM');
        }
        return new self(
            $data['deviceId'],
            $data['deviceGroupId'],
            $data['imageDigest'],
            $data['expiresAtUnixMs'],
            $data['acknowledgedRisk']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
