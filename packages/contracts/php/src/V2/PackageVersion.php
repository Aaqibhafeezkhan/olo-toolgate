<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A tenant's uploaded package version. */
final readonly class PackageVersion implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public string $packageDigest,
        public string $descriptorSetDigest,
        public PackageVersionState $state,
        public int $revision,
        public int $uploadedAtUnixMs,
        public ?PackageManifest $manifest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'packageDigest', 'descriptorSetDigest', 'state', 'revision', 'uploadedAtUnixMs', 'manifest']) || array_diff(['packageId', 'version', 'packageDigest', 'descriptorSetDigest', 'state', 'revision', 'uploadedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['packageDigest'],
            $data['descriptorSetDigest'],
            PackageVersionState::from($data['state']),
            $data['revision'],
            $data['uploadedAtUnixMs'],
            array_key_exists('manifest', $data) ? PackageManifest::fromArray($data['manifest']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
