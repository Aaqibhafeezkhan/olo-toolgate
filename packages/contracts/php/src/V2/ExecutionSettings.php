<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Execution settings with defaults resolved. */
final readonly class ExecutionSettings implements \JsonSerializable {
    public function __construct(
        public bool $async,
        public int $maxDurationMs,
        public bool $stateless,
        public bool $cancelIfAbandoned,
        public ?int $maxConcurrency = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['async', 'maxDurationMs', 'stateless', 'maxConcurrency', 'cancelIfAbandoned']) || array_diff(['async', 'maxDurationMs', 'stateless', 'cancelIfAbandoned'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['async'],
            $data['maxDurationMs'],
            $data['stateless'],
            $data['cancelIfAbandoned'],
            array_key_exists('maxConcurrency', $data) ? $data['maxConcurrency'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
