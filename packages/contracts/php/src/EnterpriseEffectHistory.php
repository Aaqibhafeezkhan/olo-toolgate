<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** One immutable authorization-generation identity and its current evidence-derived state; response digests do not prove business-receipt equivalence. */
final readonly class EnterpriseEffectHistory implements \JsonSerializable {
    public function __construct(
        public string $effectId,
        public int $generation,
        public string $attemptId,
        public string $routeId,
        public string $requestDigest,
        public EnterpriseEffectGenerationState $state,
        public EnterpriseOutcomeGrade $grade,
        public int $evidenceCount,
        public ?string $receiptDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['effectId', 'generation', 'attemptId', 'routeId', 'requestDigest', 'state', 'grade', 'evidenceCount', 'receiptDigest']) || array_diff(['effectId', 'generation', 'attemptId', 'routeId', 'requestDigest', 'state', 'grade', 'evidenceCount'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['effectId'],
            $data['generation'],
            $data['attemptId'],
            $data['routeId'],
            $data['requestDigest'],
            EnterpriseEffectGenerationState::from($data['state']),
            EnterpriseOutcomeGrade::from($data['grade']),
            $data['evidenceCount'],
            array_key_exists('receiptDigest', $data) ? $data['receiptDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
