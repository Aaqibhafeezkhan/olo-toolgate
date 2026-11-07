<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared SnapshotState bounds (plan 5.6). */
final readonly class TriggerSnapshotCapacity implements \JsonSerializable {
    public function __construct(
        public int $capacity,
        public int $maxKeyBytes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['capacity', 'maxKeyBytes']) || array_diff(['capacity', 'maxKeyBytes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['capacity'],
            $data['maxKeyBytes']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
