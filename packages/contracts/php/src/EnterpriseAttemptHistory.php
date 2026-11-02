<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Bounded attempt metadata; no bearer capabilities, raw input or target claim tokens. */
final readonly class EnterpriseAttemptHistory implements \JsonSerializable {
    public function __construct(
        public string $attemptId,
        public int $ordinal,
        public EnterpriseInvocationState $state,
        public bool $consumed,
        public int $issuedAtUnixMs,
        public int $expiresAtUnixMs,
        public int $evidenceCount,
        public EnterpriseAttemptEffectScope $scope,
        public bool $manifestSealed,
        public int $manifestEffectCount,
        public bool $settled,
        public EnterpriseOutcomeGrade $grade,
        public ?string $manifestDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['attemptId', 'ordinal', 'state', 'consumed', 'issuedAtUnixMs', 'expiresAtUnixMs', 'evidenceCount', 'scope', 'manifestSealed', 'manifestEffectCount', 'manifestDigest', 'settled', 'grade']) || array_diff(['attemptId', 'ordinal', 'state', 'consumed', 'issuedAtUnixMs', 'expiresAtUnixMs', 'evidenceCount', 'scope', 'manifestSealed', 'manifestEffectCount', 'settled', 'grade'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['attemptId'],
            $data['ordinal'],
            EnterpriseInvocationState::from($data['state']),
            $data['consumed'],
            $data['issuedAtUnixMs'],
            $data['expiresAtUnixMs'],
            $data['evidenceCount'],
            EnterpriseAttemptEffectScope::from($data['scope']),
            $data['manifestSealed'],
            $data['manifestEffectCount'],
            $data['settled'],
            EnterpriseOutcomeGrade::from($data['grade']),
            array_key_exists('manifestDigest', $data) ? $data['manifestDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
