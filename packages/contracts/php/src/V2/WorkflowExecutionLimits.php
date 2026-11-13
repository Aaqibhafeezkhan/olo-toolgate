<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Profile declaration for WorkflowExecutionLimits; eligibility and configuration confer no runtime authority. */
final readonly class WorkflowExecutionLimits implements \JsonSerializable {
    public function __construct(
        public int $maxSteps,
        public int $maxHandoffs,
        public int $maxDurationSeconds
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['maxSteps', 'maxHandoffs', 'maxDurationSeconds']) || array_diff(['maxSteps', 'maxHandoffs', 'maxDurationSeconds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['maxSteps'],
            $data['maxHandoffs'],
            $data['maxDurationSeconds']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
