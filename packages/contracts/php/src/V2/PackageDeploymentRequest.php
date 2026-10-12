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
        public ?array $hostIds = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageDigest', 'globals', 'hostIds']) || array_diff(['packageDigest', 'globals'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageDigest'],
            (object) $data['globals'],
            array_key_exists('hostIds', $data) ? array_map(static fn ($item) => $item, $data['hostIds']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
