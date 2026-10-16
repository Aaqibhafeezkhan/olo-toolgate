<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Persisted outcome of the latest profile application. state is READY only when every required package is READY. */
final readonly class ProvisioningStatus implements \JsonSerializable {
    public function __construct(
        public string $profileId,
        public string $profileDigest,
        public int $generation,
        public ProvisioningState $state,
        public array $packages,
        public int $appliedAtUnixMs,
        public ?string $previousProfileDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileId', 'profileDigest', 'generation', 'state', 'packages', 'appliedAtUnixMs', 'previousProfileDigest']) || array_diff(['profileId', 'profileDigest', 'generation', 'state', 'packages', 'appliedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['profileId'],
            $data['profileDigest'],
            $data['generation'],
            ProvisioningState::from($data['state']),
            array_map(static fn ($item) => ProvisioningPackageStatus::fromArray($item), $data['packages']),
            $data['appliedAtUnixMs'],
            array_key_exists('previousProfileDigest', $data) ? $data['previousProfileDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
