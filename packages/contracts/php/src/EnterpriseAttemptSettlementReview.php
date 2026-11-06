<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Exact closed-attempt scope attestation for independent maker/checker review; never execution authority. */
final readonly class EnterpriseAttemptSettlementReview implements \JsonSerializable {
    public function __construct(
        public int $expectedOutcomeRevision,
        public array $attemptIds,
        public bool $noFurtherEffects
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['expectedOutcomeRevision', 'attemptIds', 'noFurtherEffects']) || array_diff(['expectedOutcomeRevision', 'attemptIds', 'noFurtherEffects'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['expectedOutcomeRevision'],
            array_map(static fn ($item) => $item, $data['attemptIds']),
            $data['noFurtherEffects']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
