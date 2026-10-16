<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Local tool boundary for signed managed registrations; validate schema before use. */
final readonly class ClientDownloadManifest implements \JsonSerializable {
    public function __construct(
        public string $version,
        public array $artifacts
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['version', 'artifacts']) || array_diff(['version', 'artifacts'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['version'],
            array_map(static fn ($item) => ClientDownloadArtifact::fromArray($item), $data['artifacts'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
