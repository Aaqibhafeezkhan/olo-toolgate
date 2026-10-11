<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Where the package runs. */
final readonly class DeploymentTarget implements \JsonSerializable {
    public function __construct(
        public TargetKind $kind,
        public array $ids,
        public array $allowedTiers
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'ids', 'allowedTiers']) || array_diff(['kind', 'ids', 'allowedTiers'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TargetKind::from($data['kind']),
            array_map(static fn ($item) => $item, $data['ids']),
            array_map(static fn ($item) => RuntimeTier::from($item), $data['allowedTiers'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
