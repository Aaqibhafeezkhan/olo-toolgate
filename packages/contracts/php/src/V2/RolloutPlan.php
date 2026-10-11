<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Rollout plan attached at enable. */
final readonly class RolloutPlan implements \JsonSerializable {
    public function __construct(
        public RolloutStrategy $strategy,
        public array $steps,
        public bool $autoRollback
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['strategy', 'steps', 'autoRollback']) || array_diff(['strategy', 'steps', 'autoRollback'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            RolloutStrategy::from($data['strategy']),
            array_map(static fn ($item) => RolloutStep::fromArray($item), $data['steps']),
            $data['autoRollback']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
