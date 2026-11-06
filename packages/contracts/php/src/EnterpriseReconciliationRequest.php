<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Immutable independently reviewed downstream evidence. Never creates a new permit. */
final readonly class EnterpriseReconciliationRequest implements \JsonSerializable {
    public function __construct(
        public int $expectedRevision,
        public EnterpriseReconciledState $state,
        public array $completedResources,
        public string $evidenceDigest,
        public ?string $resultDigest = null,
        public ?EnterpriseAttemptSettlementReview $attemptSettlement = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['expectedRevision', 'state', 'completedResources', 'evidenceDigest', 'resultDigest', 'attemptSettlement']) || array_diff(['expectedRevision', 'state', 'completedResources', 'evidenceDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['expectedRevision'],
            EnterpriseReconciledState::from($data['state']),
            array_map(static fn ($item) => ResourceDescriptor::fromArray($item), $data['completedResources']),
            $data['evidenceDigest'],
            array_key_exists('resultDigest', $data) ? $data['resultDigest'] : null,
            array_key_exists('attemptSettlement', $data) ? EnterpriseAttemptSettlementReview::fromArray($data['attemptSettlement']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
