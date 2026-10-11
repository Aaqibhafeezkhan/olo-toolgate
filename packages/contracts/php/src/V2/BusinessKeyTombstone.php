<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Kept after retention; results purged. */
final readonly class BusinessKeyTombstone implements \JsonSerializable {
    public function __construct(
        public string $businessKeyHash,
        public string $fingerprint,
        public TaskState $outcome,
        public int $createdAtUnixMs,
        public int $purgedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['businessKeyHash', 'fingerprint', 'outcome', 'createdAtUnixMs', 'purgedAtUnixMs']) || array_diff(['businessKeyHash', 'fingerprint', 'outcome', 'createdAtUnixMs', 'purgedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['businessKeyHash'],
            $data['fingerprint'],
            TaskState::from($data['outcome']),
            $data['createdAtUnixMs'],
            $data['purgedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
