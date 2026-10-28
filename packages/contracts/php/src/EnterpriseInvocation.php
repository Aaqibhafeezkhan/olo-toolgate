<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Durable exact binding and outcome state. OUTCOME_UNKNOWN never authorizes a blind retry. */
final readonly class EnterpriseInvocation implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $requestDigest,
        public EnterpriseEvaluation $evaluation,
        public EnterpriseInvocationState $state,
        public int $revision,
        public int $authorizationEpoch,
        public int $expiresAtUnixMs,
        public array $completedResources,
        public string $diagnosticId,
        public ?string $reservedNonce = null,
        public ?string $downstreamIdempotencyKey = null,
        public ?string $resultDigest = null,
        public ?ReconciliationMethod $reconciliation = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'requestDigest', 'evaluation', 'state', 'revision', 'authorizationEpoch', 'reservedNonce', 'expiresAtUnixMs', 'completedResources', 'downstreamIdempotencyKey', 'resultDigest', 'diagnosticId', 'reconciliation']) || array_diff(['id', 'requestDigest', 'evaluation', 'state', 'revision', 'authorizationEpoch', 'expiresAtUnixMs', 'completedResources', 'diagnosticId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['requestDigest'],
            EnterpriseEvaluation::fromArray($data['evaluation']),
            EnterpriseInvocationState::from($data['state']),
            $data['revision'],
            $data['authorizationEpoch'],
            $data['expiresAtUnixMs'],
            array_map(static fn ($item) => ResourceDescriptor::fromArray($item), $data['completedResources']),
            $data['diagnosticId'],
            array_key_exists('reservedNonce', $data) ? $data['reservedNonce'] : null,
            array_key_exists('downstreamIdempotencyKey', $data) ? $data['downstreamIdempotencyKey'] : null,
            array_key_exists('resultDigest', $data) ? $data['resultDigest'] : null,
            array_key_exists('reconciliation', $data) ? ReconciliationMethod::from($data['reconciliation']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
