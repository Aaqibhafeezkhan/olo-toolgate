<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Sequences of an attempt that never arrived; they are reported, never invented. */
final readonly class RunEventGap implements \JsonSerializable {
    public function __construct(
        public int $attempt,
        public int $fromSequence,
        public int $toSequence
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['attempt', 'fromSequence', 'toSequence']) || array_diff(['attempt', 'fromSequence', 'toSequence'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['attempt'],
            $data['fromSequence'],
            $data['toSequence']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
