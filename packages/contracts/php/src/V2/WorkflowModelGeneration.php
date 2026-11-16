<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explicit bounded inference parameters selected from the pinned model deployment, never from a runner request. */
final readonly class WorkflowModelGeneration implements \JsonSerializable {
    public function __construct(
        public int $maxOutputTokens,
        public float $temperature
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['maxOutputTokens', 'temperature']) || array_diff(['maxOutputTokens', 'temperature'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['maxOutputTokens'],
            $data['temperature']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
