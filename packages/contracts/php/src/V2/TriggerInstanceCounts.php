<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Instances of an assignment by phase. */
final readonly class TriggerInstanceCounts implements \JsonSerializable {
    public function __construct(
        public int $desired,
        public int $pending,
        public int $starting,
        public int $running,
        public int $paused,
        public int $stopped,
        public int $failed,
        public int $unsupported,
        public int $stale
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['desired', 'pending', 'starting', 'running', 'paused', 'stopped', 'failed', 'unsupported', 'stale']) || array_diff(['desired', 'pending', 'starting', 'running', 'paused', 'stopped', 'failed', 'unsupported', 'stale'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['desired'],
            $data['pending'],
            $data['starting'],
            $data['running'],
            $data['paused'],
            $data['stopped'],
            $data['failed'],
            $data['unsupported'],
            $data['stale']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
