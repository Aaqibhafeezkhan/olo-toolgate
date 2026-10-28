<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One package difference from the previously applied profile, recorded for review. */
final readonly class ProfileChange implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public ProfileChangeKind $change,
        public ?string $previousVersion = null,
        public ?string $previousPackageDigest = null,
        public ?string $version = null,
        public ?string $packageDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'change', 'previousVersion', 'previousPackageDigest', 'version', 'packageDigest']) || array_diff(['packageId', 'change'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            ProfileChangeKind::from($data['change']),
            array_key_exists('previousVersion', $data) ? $data['previousVersion'] : null,
            array_key_exists('previousPackageDigest', $data) ? $data['previousPackageDigest'] : null,
            array_key_exists('version', $data) ? $data['version'] : null,
            array_key_exists('packageDigest', $data) ? $data['packageDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
