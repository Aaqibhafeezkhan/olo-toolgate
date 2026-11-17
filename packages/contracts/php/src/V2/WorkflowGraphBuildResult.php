<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Compilation receipt; this neither publishes configuration nor grants runtime authority. */
final readonly class WorkflowGraphBuildResult implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $profileId,
        public string $profileDigest,
        public string $workflowId,
        public int $generation,
        public string $specificationDigest,
        public string $compilerDigest,
        public array $features,
        public string $state
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'profileId', 'profileDigest', 'workflowId', 'generation', 'specificationDigest', 'compilerDigest', 'features', 'state']) || array_diff(['tenantId', 'profileId', 'profileDigest', 'workflowId', 'generation', 'specificationDigest', 'compilerDigest', 'features', 'state'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['state'] !== 'READY') {
            throw new \InvalidArgumentException('state must be READY');
        }
        return new self(
            $data['tenantId'],
            $data['profileId'],
            $data['profileDigest'],
            $data['workflowId'],
            $data['generation'],
            $data['specificationDigest'],
            $data['compilerDigest'],
            array_map(static fn ($item) => $item, $data['features']),
            $data['state']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
