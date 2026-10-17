<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A client release: the image index pinned by digest, the exact FleetPackageDocument bytes (manifestDigest) and, when Control holds the package-release key, the signed FleetPackageRelease it recorded. */
final readonly class ClientRelease implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public string $packageDigest,
        public string $image,
        public string $runtimeVersion,
        public string $manifestDigest,
        public bool $signed,
        public \stdClass $document,
        public string $documentBase64,
        public array $tools,
        public ?\stdClass $release = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'packageDigest', 'image', 'runtimeVersion', 'manifestDigest', 'signed', 'document', 'documentBase64', 'release', 'tools']) || array_diff(['packageId', 'version', 'packageDigest', 'image', 'runtimeVersion', 'manifestDigest', 'signed', 'document', 'documentBase64', 'tools'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['packageDigest'],
            $data['image'],
            $data['runtimeVersion'],
            $data['manifestDigest'],
            $data['signed'],
            (object) $data['document'],
            $data['documentBase64'],
            array_map(static fn ($item) => ClientReleaseTool::fromArray($item), $data['tools']),
            array_key_exists('release', $data) ? (object) $data['release'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
