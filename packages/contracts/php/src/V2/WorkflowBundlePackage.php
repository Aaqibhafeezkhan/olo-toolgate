<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** WorkflowBundlePackage portable metadata. */
final readonly class WorkflowBundlePackage implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public string $packageDigest,
        public string $sha256,
        public int $bytes,
        public string $path
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'packageDigest', 'sha256', 'bytes', 'path']) || array_diff(['packageId', 'version', 'packageDigest', 'sha256', 'bytes', 'path'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['packageDigest'],
            $data['sha256'],
            $data['bytes'],
            $data['path']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
