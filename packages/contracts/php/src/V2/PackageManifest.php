<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Package manifest v2. v1 manifests stay readable and are upgraded on read. */
final readonly class PackageManifest implements \JsonSerializable {
    public function __construct(
        public int $schemaVersion,
        public PackageKind $kind,
        public string $id,
        public string $version,
        public string $publisher,
        public PackageCompatibility $compatibility,
        public array $runtimes,
        public array $tools,
        public string $descriptorSetDigest,
        public array $artifacts,
        public array $credentialReferences,
        public array $connections,
        public array $composition,
        public PackageLimits $limits,
        public ?SdkInfo $sdk = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['schemaVersion', 'kind', 'id', 'version', 'publisher', 'sdk', 'compatibility', 'runtimes', 'tools', 'descriptorSetDigest', 'artifacts', 'credentialReferences', 'connections', 'composition', 'limits']) || array_diff(['schemaVersion', 'kind', 'id', 'version', 'publisher', 'compatibility', 'runtimes', 'tools', 'descriptorSetDigest', 'artifacts', 'credentialReferences', 'connections', 'composition', 'limits'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['schemaVersion'] !== 2) {
            throw new \InvalidArgumentException('schemaVersion must be 2');
        }
        return new self(
            $data['schemaVersion'],
            PackageKind::from($data['kind']),
            $data['id'],
            $data['version'],
            $data['publisher'],
            PackageCompatibility::fromArray($data['compatibility']),
            array_map(static fn ($item) => RuntimeDeclaration::fromArray($item), $data['runtimes']),
            array_map(static fn ($item) => ToolDefinition::fromArray($item), $data['tools']),
            $data['descriptorSetDigest'],
            array_map(static fn ($item) => PackagedArtifact::fromArray($item), $data['artifacts']),
            array_map(static fn ($item) => $item, $data['credentialReferences']),
            array_map(static fn ($item) => ConnectionRequirement::fromArray($item), $data['connections']),
            array_map(static fn ($item) => CompositionRecord::fromArray($item), $data['composition']),
            PackageLimits::fromArray($data['limits']),
            array_key_exists('sdk', $data) ? SdkInfo::fromArray($data['sdk']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
