<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Exactly one enrollment code or existing device identifier for establishing console device context. */
final readonly class ConsoleDeviceLookup implements \JsonSerializable {
    public function __construct(
        public ?string $userCode = null,
        public ?string $deviceId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['userCode', 'deviceId']) || array_diff([], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_key_exists('userCode', $data) ? $data['userCode'] : null,
            array_key_exists('deviceId', $data) ? $data['deviceId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
