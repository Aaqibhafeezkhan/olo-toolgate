<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** What a registered Tool Host runs: its tools, and the deployments whose package, service profile and globals it fetches by digest. */
final readonly class ToolHostAssignments implements \JsonSerializable {
    public function __construct(
        public string $hostId,
        public array $tools,
        public array $deployments
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['hostId', 'tools', 'deployments']) || array_diff(['hostId', 'tools', 'deployments'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['hostId'],
            array_map(static fn ($item) => ToolHostAssignment::fromArray($item), $data['tools']),
            array_map(static fn ($item) => PackageDeployment::fromArray($item), $data['deployments'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
