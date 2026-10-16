<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Control acknowledgement: the highest contiguous sequence stored for the attempt, and how many frames were already present or refused by retention bounds. */
final readonly class RunEventAck implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public int $attempt,
        public int $acceptedThrough,
        public int $duplicates,
        public int $refused
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'attempt', 'acceptedThrough', 'duplicates', 'refused']) || array_diff(['invocationId', 'attempt', 'acceptedThrough', 'duplicates', 'refused'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['attempt'],
            $data['acceptedThrough'],
            $data['duplicates'],
            $data['refused']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
