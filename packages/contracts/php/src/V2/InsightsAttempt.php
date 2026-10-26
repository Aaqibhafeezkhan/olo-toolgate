<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One execution attempt: one consumed permit. */
final readonly class InsightsAttempt implements \JsonSerializable {
    public function __construct(
        public int $attemptNumber,
        public ?string $executionId = null,
        public ?string $executorId = null,
        public ?int $startedAtUnixMs = null,
        public ?int $endedAtUnixMs = null,
        public ?InsightsOutcome $outcome = null,
        public ?int $durationMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['attemptNumber', 'executionId', 'executorId', 'startedAtUnixMs', 'endedAtUnixMs', 'outcome', 'durationMs']) || array_diff(['attemptNumber'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['attemptNumber'],
            array_key_exists('executionId', $data) ? $data['executionId'] : null,
            array_key_exists('executorId', $data) ? $data['executorId'] : null,
            array_key_exists('startedAtUnixMs', $data) ? $data['startedAtUnixMs'] : null,
            array_key_exists('endedAtUnixMs', $data) ? $data['endedAtUnixMs'] : null,
            array_key_exists('outcome', $data) ? InsightsOutcome::from($data['outcome']) : null,
            array_key_exists('durationMs', $data) ? $data['durationMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
