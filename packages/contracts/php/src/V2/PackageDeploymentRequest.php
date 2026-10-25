<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Deploys an uploaded server package to the Tool Hosts with values for its declared globals. A destination's host is the global its hostSetting names. */
final readonly class PackageDeploymentRequest implements \JsonSerializable {
    public function __construct(
        public string $packageDigest,
        public \stdClass $globals,
        public ?array $hostIds = null,
        public ?array $deviceGroupIds = null,
        public ?array $privateRanges = null,
        public ?\stdClass $destinationTls = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageDigest', 'globals', 'hostIds', 'deviceGroupIds', 'privateRanges', 'destinationTls']) || array_diff(['packageDigest', 'globals'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageDigest'],
            (object) $data['globals'],
            array_key_exists('hostIds', $data) ? array_map(static fn ($item) => $item, $data['hostIds']) : null,
            array_key_exists('deviceGroupIds', $data) ? array_map(static fn ($item) => $item, $data['deviceGroupIds']) : null,
            array_key_exists('privateRanges', $data) ? array_map(static fn ($item) => PrivateRangeApproval::fromArray($item), $data['privateRanges']) : null,
            array_key_exists('destinationTls', $data) ? (object) $data['destinationTls'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
