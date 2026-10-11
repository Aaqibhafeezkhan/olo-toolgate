<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Exact code and configuration the attempt may run. */
final readonly class PermitTarget implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public string $toolDescriptorDigest,
        public string $packageDigest,
        public string $artifactDigest,
        public string $runtimeDigest,
        public string $globalsDigest,
        public string $serviceProfileDigest,
        public int $serviceProfileRevision,
        public string $deploymentBindingDigest,
        public int $deploymentBindingRevision,
        public int $configRevision,
        public array $routes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'toolDescriptorDigest', 'packageDigest', 'artifactDigest', 'runtimeDigest', 'globalsDigest', 'serviceProfileDigest', 'serviceProfileRevision', 'deploymentBindingDigest', 'deploymentBindingRevision', 'configRevision', 'routes']) || array_diff(['toolId', 'toolDescriptorDigest', 'packageDigest', 'artifactDigest', 'runtimeDigest', 'globalsDigest', 'serviceProfileDigest', 'serviceProfileRevision', 'deploymentBindingDigest', 'deploymentBindingRevision', 'configRevision', 'routes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['toolDescriptorDigest'],
            $data['packageDigest'],
            $data['artifactDigest'],
            $data['runtimeDigest'],
            $data['globalsDigest'],
            $data['serviceProfileDigest'],
            $data['serviceProfileRevision'],
            $data['deploymentBindingDigest'],
            $data['deploymentBindingRevision'],
            $data['configRevision'],
            array_map(static fn ($item) => $item, $data['routes'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
