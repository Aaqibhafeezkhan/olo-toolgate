<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Public deployment details attached only to an already visible enrolled device. */
final readonly class DeviceDeploymentRecord implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public DeviceDeploymentMetadata $deployment
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'deployment']) || array_diff(['deviceId', 'deployment'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deviceId'],
            DeviceDeploymentMetadata::fromArray($data['deployment'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
