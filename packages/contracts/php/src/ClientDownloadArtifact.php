<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Local tool boundary for signed managed registrations; validate schema before use. */
final readonly class ClientDownloadArtifact implements \JsonSerializable {
    public function __construct(
        public ClientPlatform $platform,
        public string $target,
        public string $filename,
        public string $sha256,
        public int $bytes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['platform', 'target', 'filename', 'sha256', 'bytes']) || array_diff(['platform', 'target', 'filename', 'sha256', 'bytes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ClientPlatform::from($data['platform']),
            $data['target'],
            $data['filename'],
            $data['sha256'],
            $data['bytes']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
