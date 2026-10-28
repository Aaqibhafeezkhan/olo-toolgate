<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Opt-in administrative deployment view. Default device responses retain their existing closed schema. */
final readonly class EndpointManagedDeviceDeploymentPage implements \JsonSerializable {
    public function __construct(
        public array $items,
        public ?array $deployments = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['items', 'deployments']) || array_diff(['items'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => EndpointManagedDevice::fromArray($item), $data['items']),
            array_key_exists('deployments', $data) ? array_map(static fn ($item) => DeviceDeploymentRecord::fromArray($item), $data['deployments']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
