<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One pinned package: where provisioning stands and why it is not ready. hostIds and globalsDigest record the deployment it applied, so an unchanged repeat does not redeploy. */
final readonly class ProvisioningPackageStatus implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public bool $required,
        public ProvisioningState $state,
        public ProvisioningStep $step,
        public ?string $packageDigest = null,
        public ?string $reason = null,
        public ?array $toolIds = null,
        public ?array $hostIds = null,
        public ?string $globalsDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'packageDigest', 'required', 'state', 'step', 'reason', 'toolIds', 'hostIds', 'globalsDigest']) || array_diff(['packageId', 'version', 'required', 'state', 'step'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['required'],
            ProvisioningState::from($data['state']),
            ProvisioningStep::from($data['step']),
            array_key_exists('packageDigest', $data) ? $data['packageDigest'] : null,
            array_key_exists('reason', $data) ? $data['reason'] : null,
            array_key_exists('toolIds', $data) ? array_map(static fn ($item) => $item, $data['toolIds']) : null,
            array_key_exists('hostIds', $data) ? array_map(static fn ($item) => $item, $data['hostIds']) : null,
            array_key_exists('globalsDigest', $data) ? $data['globalsDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
