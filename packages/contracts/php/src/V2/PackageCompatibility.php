<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Version requirements checked at enable. */
final readonly class PackageCompatibility implements \JsonSerializable {
    public function __construct(
        public string $contractsVersion,
        public ?string $minimumToolHostVersion = null,
        public ?string $minimumClientVersion = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['contractsVersion', 'minimumToolHostVersion', 'minimumClientVersion']) || array_diff(['contractsVersion'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['contractsVersion'],
            array_key_exists('minimumToolHostVersion', $data) ? $data['minimumToolHostVersion'] : null,
            array_key_exists('minimumClientVersion', $data) ? $data['minimumClientVersion'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
