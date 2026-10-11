<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Effective Phase A check-in values. Env-owned, read-only in the console. */
final readonly class ClientCheckIn implements \JsonSerializable {
    public function __construct(
        public int $checkInMs,
        public int $jitterPct,
        public int $offlineAfterMs,
        public ValueSource $source
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['checkInMs', 'jitterPct', 'offlineAfterMs', 'source']) || array_diff(['checkInMs', 'jitterPct', 'offlineAfterMs', 'source'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['checkInMs'],
            $data['jitterPct'],
            $data['offlineAfterMs'],
            ValueSource::from($data['source'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
