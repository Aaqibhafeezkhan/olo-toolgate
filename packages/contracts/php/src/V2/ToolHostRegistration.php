<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool Host registration with its workload identity (mTLS). */
final readonly class ToolHostRegistration implements \JsonSerializable {
    public function __construct(
        public string $hostId,
        public string $poolId,
        public string $version,
        public ProtocolRange $protocol,
        public string $launcherVersion,
        public array $tiers
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['hostId', 'poolId', 'version', 'protocol', 'launcherVersion', 'tiers']) || array_diff(['hostId', 'poolId', 'version', 'protocol', 'launcherVersion', 'tiers'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['hostId'],
            $data['poolId'],
            $data['version'],
            ProtocolRange::fromArray($data['protocol']),
            $data['launcherVersion'],
            array_map(static fn ($item) => RuntimeTier::from($item), $data['tiers'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
