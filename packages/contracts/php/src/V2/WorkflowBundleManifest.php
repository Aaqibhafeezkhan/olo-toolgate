<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** WorkflowBundleManifest portable metadata. */
final readonly class WorkflowBundleManifest implements \JsonSerializable {
    public function __construct(
        public string $format,
        public int $version,
        public string $workflowId,
        public string $sha256,
        public string $referencesSha256,
        public array $connections,
        public array $packages,
        public ?WorkflowBundleBuild $build = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['format', 'version', 'workflowId', 'sha256', 'referencesSha256', 'build', 'connections', 'packages']) || array_diff(['format', 'version', 'workflowId', 'sha256', 'referencesSha256', 'connections', 'packages'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['format'] !== 'olo-workflow') {
            throw new \InvalidArgumentException('format must be olo-workflow');
        }
        if ($data['version'] !== 2) {
            throw new \InvalidArgumentException('version must be 2');
        }
        return new self(
            $data['format'],
            $data['version'],
            $data['workflowId'],
            $data['sha256'],
            $data['referencesSha256'],
            array_map(static fn ($item) => WorkflowBundleConnection::fromArray($item), $data['connections']),
            array_map(static fn ($item) => WorkflowBundlePackage::fromArray($item), $data['packages']),
            array_key_exists('build', $data) ? WorkflowBundleBuild::fromArray($data['build']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
