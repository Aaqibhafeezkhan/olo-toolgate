<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Control-derived business outcome; metadata never grants execution authority or automatic retry. */
final readonly class EnterpriseOperationOutcome implements \JsonSerializable {
    public function __construct(
        public string $operationId,
        public EnterpriseOperationState $state,
        public int $revision,
        public EnterpriseOutcomeGrade $grade,
        public EnterpriseExecutionCoverage $coverage,
        public bool $journalComplete,
        public int $attemptCount,
        public int $effectCount,
        public int $fencedEffectCount,
        public int $unfencedEffectCount,
        public int $unresolvedEffectCount,
        public int $unsettledAttemptCount,
        public bool $finalized,
        public bool $retryable,
        public bool $deduplicationAssumed,
        public int $updatedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['operationId', 'state', 'revision', 'grade', 'coverage', 'journalComplete', 'attemptCount', 'effectCount', 'fencedEffectCount', 'unfencedEffectCount', 'unresolvedEffectCount', 'unsettledAttemptCount', 'finalized', 'retryable', 'deduplicationAssumed', 'updatedAtUnixMs']) || array_diff(['operationId', 'state', 'revision', 'grade', 'coverage', 'journalComplete', 'attemptCount', 'effectCount', 'fencedEffectCount', 'unfencedEffectCount', 'unresolvedEffectCount', 'unsettledAttemptCount', 'finalized', 'retryable', 'deduplicationAssumed', 'updatedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['operationId'],
            EnterpriseOperationState::from($data['state']),
            $data['revision'],
            EnterpriseOutcomeGrade::from($data['grade']),
            EnterpriseExecutionCoverage::from($data['coverage']),
            $data['journalComplete'],
            $data['attemptCount'],
            $data['effectCount'],
            $data['fencedEffectCount'],
            $data['unfencedEffectCount'],
            $data['unresolvedEffectCount'],
            $data['unsettledAttemptCount'],
            $data['finalized'],
            $data['retryable'],
            $data['deduplicationAssumed'],
            $data['updatedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
