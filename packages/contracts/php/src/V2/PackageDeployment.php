<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** The package version Tool Hosts run for one package id, with the service profile and globals Control built for it. */
final readonly class PackageDeployment implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public string $packageDigest,
        public int $revision,
        public string $serviceProfileDigest,
        public int $serviceProfileRevision,
        public string $globalsDigest,
        public array $toolIds,
        public int $deployedAtUnixMs,
        public ?array $hostIds = null,
        public ?array $deviceGroupIds = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'packageDigest', 'revision', 'serviceProfileDigest', 'serviceProfileRevision', 'globalsDigest', 'toolIds', 'deployedAtUnixMs', 'hostIds', 'deviceGroupIds']) || array_diff(['packageId', 'version', 'packageDigest', 'revision', 'serviceProfileDigest', 'serviceProfileRevision', 'globalsDigest', 'toolIds', 'deployedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['packageDigest'],
            $data['revision'],
            $data['serviceProfileDigest'],
            $data['serviceProfileRevision'],
            $data['globalsDigest'],
            array_map(static fn ($item) => $item, $data['toolIds']),
            $data['deployedAtUnixMs'],
            array_key_exists('hostIds', $data) ? array_map(static fn ($item) => $item, $data['hostIds']) : null,
            array_key_exists('deviceGroupIds', $data) ? array_map(static fn ($item) => $item, $data['deviceGroupIds']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
