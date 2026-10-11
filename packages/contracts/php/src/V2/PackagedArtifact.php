<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Executable artifact inside the package; artifactDigest = sha256 over its exact bytes. */
final readonly class PackagedArtifact implements \JsonSerializable {
    public function __construct(
        public string $artifactId,
        public string $path,
        public string $artifactDigest,
        public int $sizeBytes,
        public string $mediaType,
        public ?string $runtimeId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['artifactId', 'path', 'artifactDigest', 'sizeBytes', 'mediaType', 'runtimeId']) || array_diff(['artifactId', 'path', 'artifactDigest', 'sizeBytes', 'mediaType'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['artifactId'],
            $data['path'],
            $data['artifactDigest'],
            $data['sizeBytes'],
            $data['mediaType'],
            array_key_exists('runtimeId', $data) ? $data['runtimeId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
