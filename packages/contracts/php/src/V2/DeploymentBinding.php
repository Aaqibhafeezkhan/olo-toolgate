<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Deployment binding. */
final readonly class DeploymentBinding implements \JsonSerializable {
    public function __construct(
        public int $bindingFormatVersion,
        public string $bindingId,
        public int $revision,
        public string $tenantId,
        public string $packageId,
        public string $packageDigest,
        public int $configRevision,
        public string $serviceProfileDigest,
        public int $serviceProfileRevision,
        public DeploymentTarget $target,
        public array $grants,
        public array $limitOverrides,
        public RolloutPlan $rolloutPlan,
        public array $effects
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['bindingFormatVersion', 'bindingId', 'revision', 'tenantId', 'packageId', 'packageDigest', 'configRevision', 'serviceProfileDigest', 'serviceProfileRevision', 'target', 'grants', 'limitOverrides', 'rolloutPlan', 'effects']) || array_diff(['bindingFormatVersion', 'bindingId', 'revision', 'tenantId', 'packageId', 'packageDigest', 'configRevision', 'serviceProfileDigest', 'serviceProfileRevision', 'target', 'grants', 'limitOverrides', 'rolloutPlan', 'effects'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['bindingFormatVersion'] !== 1) {
            throw new \InvalidArgumentException('bindingFormatVersion must be 1');
        }
        return new self(
            $data['bindingFormatVersion'],
            $data['bindingId'],
            $data['revision'],
            $data['tenantId'],
            $data['packageId'],
            $data['packageDigest'],
            $data['configRevision'],
            $data['serviceProfileDigest'],
            $data['serviceProfileRevision'],
            DeploymentTarget::fromArray($data['target']),
            array_map(static fn ($item) => DeploymentGrant::fromArray($item), $data['grants']),
            array_map(static fn ($item) => LimitOverride::fromArray($item), $data['limitOverrides']),
            RolloutPlan::fromArray($data['rolloutPlan']),
            array_map(static fn ($item) => EffectApproval::fromArray($item), $data['effects'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
