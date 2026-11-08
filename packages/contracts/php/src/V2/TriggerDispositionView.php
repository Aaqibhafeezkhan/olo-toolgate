<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Control's terminal disposition of a stored record: a loss record, or a Control-side rejection on re-validation (plan 6.6). */
final readonly class TriggerDispositionView implements \JsonSerializable {
    public function __construct(
        public TriggerDispositionKind $disposition,
        public string $reason
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['disposition', 'reason']) || array_diff(['disposition', 'reason'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerDispositionKind::from($data['disposition']),
            $data['reason']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
