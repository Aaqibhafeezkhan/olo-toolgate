<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Monotonic counters for the current process lifetime of one instance. */
final readonly class TriggerCounters implements \JsonSerializable {
    public function __construct(
        public int $emitted,
        public int $rejected,
        public int $dropped,
        public int $restarts,
        public ?int $emitTimeouts = null,
        public ?int $brokerCalls = null,
        public ?int $possibleLossOnCrash = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['emitted', 'rejected', 'dropped', 'emitTimeouts', 'restarts', 'brokerCalls', 'possibleLossOnCrash']) || array_diff(['emitted', 'rejected', 'dropped', 'restarts'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['emitted'],
            $data['rejected'],
            $data['dropped'],
            $data['restarts'],
            array_key_exists('emitTimeouts', $data) ? $data['emitTimeouts'] : null,
            array_key_exists('brokerCalls', $data) ? $data['brokerCalls'] : null,
            array_key_exists('possibleLossOnCrash', $data) ? $data['possibleLossOnCrash'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
