<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Deployment-time package provisioning for one profile (plan.md §7). Applied by Control at deployment; removing an entry never uninstalls a package. */
final readonly class DeploymentProfile implements \JsonSerializable {
    public function __construct(
        public int $profileVersion,
        public string $profileId,
        public array $packages,
        public ?array $readiness = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileVersion', 'profileId', 'packages', 'readiness']) || array_diff(['profileVersion', 'profileId', 'packages'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['profileVersion'] !== 1) {
            throw new \InvalidArgumentException('profileVersion must be 1');
        }
        return new self(
            $data['profileVersion'],
            $data['profileId'],
            array_map(static fn ($item) => ProfilePackage::fromArray($item), $data['packages']),
            array_key_exists('readiness', $data) ? array_map(static fn ($item) => ProfileReadinessCase::fromArray($item), $data['readiness']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
