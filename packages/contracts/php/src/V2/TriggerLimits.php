<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared per-instance limits. */
final readonly class TriggerLimits implements \JsonSerializable {
    public function __construct(
        public int $maxEventsPerHour,
        public int $maxEventBytes,
        public int $maxCheckpointBytes,
        public int $stopGraceMs,
        public ?int $memoryMiB = null,
        public ?int $maxBrokerCallsPerMinute = null,
        public ?int $emitTimeoutMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['maxEventsPerHour', 'maxEventBytes', 'maxCheckpointBytes', 'memoryMiB', 'maxBrokerCallsPerMinute', 'stopGraceMs', 'emitTimeoutMs']) || array_diff(['maxEventsPerHour', 'maxEventBytes', 'maxCheckpointBytes', 'stopGraceMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['maxEventsPerHour'],
            $data['maxEventBytes'],
            $data['maxCheckpointBytes'],
            $data['stopGraceMs'],
            array_key_exists('memoryMiB', $data) ? $data['memoryMiB'] : null,
            array_key_exists('maxBrokerCallsPerMinute', $data) ? $data['maxBrokerCallsPerMinute'] : null,
            array_key_exists('emitTimeoutMs', $data) ? $data['emitTimeoutMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
