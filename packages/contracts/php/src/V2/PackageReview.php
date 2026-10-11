<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Review and enable page data: both diffs and the values to fill. */
final readonly class PackageReview implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public string $packageDigest,
        public ApiChange $apiChange,
        public array $securityDelta,
        public DeploymentBinding $bindingDraft,
        public ?string $previousPackageDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'packageDigest', 'previousPackageDigest', 'apiChange', 'securityDelta', 'bindingDraft']) || array_diff(['packageId', 'version', 'packageDigest', 'apiChange', 'securityDelta', 'bindingDraft'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['packageDigest'],
            ApiChange::from($data['apiChange']),
            array_map(static fn ($item) => SecurityDeltaItem::fromArray($item), $data['securityDelta']),
            DeploymentBinding::fromArray($data['bindingDraft']),
            array_key_exists('previousPackageDigest', $data) ? $data['previousPackageDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
