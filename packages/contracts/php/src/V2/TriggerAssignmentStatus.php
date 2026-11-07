<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Observed assignment status. */
final readonly class TriggerAssignmentStatus implements \JsonSerializable {
    public function __construct(
        public int $observedGeneration,
        public TriggerInstanceCounts $instances,
        public array $conditions
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['observedGeneration', 'instances', 'conditions']) || array_diff(['observedGeneration', 'instances', 'conditions'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['observedGeneration'],
            TriggerInstanceCounts::fromArray($data['instances']),
            array_map(static fn ($item) => TriggerAssignmentCondition::fromArray($item), $data['conditions'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
