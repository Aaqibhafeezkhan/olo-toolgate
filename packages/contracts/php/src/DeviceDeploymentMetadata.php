<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Operator-collected Docker deployment details for display only; never enrollment, permission or trusted network evidence. */
final readonly class DeviceDeploymentMetadata implements \JsonSerializable {
    public function __construct(
        public DeviceExecutionKind $executionKind,
        public string $containerId,
        public string $containerName,
        public string $systemName,
        public string $networkId,
        public string $networkName,
        public string $ipAddress
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['executionKind', 'containerId', 'containerName', 'systemName', 'networkId', 'networkName', 'ipAddress']) || array_diff(['executionKind', 'containerId', 'containerName', 'systemName', 'networkId', 'networkName', 'ipAddress'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            DeviceExecutionKind::from($data['executionKind']),
            $data['containerId'],
            $data['containerName'],
            $data['systemName'],
            $data['networkId'],
            $data['networkName'],
            $data['ipAddress']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
