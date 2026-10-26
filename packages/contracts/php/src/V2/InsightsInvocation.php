<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One invocation's summary, projected from its transition facts. Durations are absent when not measured. */
final readonly class InsightsInvocation implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $invocationId,
        public string $toolId,
        public string $action,
        public InsightsOutcome $outcome,
        public int $attempts,
        public int $acceptedAtUnixMs,
        public bool $partialCoverage,
        public ?string $clientId = null,
        public ?string $userId = null,
        public ?string $agentId = null,
        public ?string $toolCallId = null,
        public ?string $sessionId = null,
        public ?string $agentRunId = null,
        public ?InsightsErrorCategory $errorCategory = null,
        public ?int $approvalRequestedAtUnixMs = null,
        public ?int $approvalResolvedAtUnixMs = null,
        public ?int $queuedAtUnixMs = null,
        public ?int $firstStartedAtUnixMs = null,
        public ?int $terminalAtUnixMs = null,
        public ?int $approvalMs = null,
        public ?int $queueMs = null,
        public ?int $executionMs = null,
        public ?int $endToEndMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'invocationId', 'toolId', 'action', 'clientId', 'userId', 'agentId', 'toolCallId', 'sessionId', 'agentRunId', 'outcome', 'errorCategory', 'attempts', 'acceptedAtUnixMs', 'approvalRequestedAtUnixMs', 'approvalResolvedAtUnixMs', 'queuedAtUnixMs', 'firstStartedAtUnixMs', 'terminalAtUnixMs', 'approvalMs', 'queueMs', 'executionMs', 'endToEndMs', 'partialCoverage']) || array_diff(['tenantId', 'invocationId', 'toolId', 'action', 'outcome', 'attempts', 'acceptedAtUnixMs', 'partialCoverage'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['invocationId'],
            $data['toolId'],
            $data['action'],
            InsightsOutcome::from($data['outcome']),
            $data['attempts'],
            $data['acceptedAtUnixMs'],
            $data['partialCoverage'],
            array_key_exists('clientId', $data) ? $data['clientId'] : null,
            array_key_exists('userId', $data) ? $data['userId'] : null,
            array_key_exists('agentId', $data) ? $data['agentId'] : null,
            array_key_exists('toolCallId', $data) ? $data['toolCallId'] : null,
            array_key_exists('sessionId', $data) ? $data['sessionId'] : null,
            array_key_exists('agentRunId', $data) ? $data['agentRunId'] : null,
            array_key_exists('errorCategory', $data) ? InsightsErrorCategory::from($data['errorCategory']) : null,
            array_key_exists('approvalRequestedAtUnixMs', $data) ? $data['approvalRequestedAtUnixMs'] : null,
            array_key_exists('approvalResolvedAtUnixMs', $data) ? $data['approvalResolvedAtUnixMs'] : null,
            array_key_exists('queuedAtUnixMs', $data) ? $data['queuedAtUnixMs'] : null,
            array_key_exists('firstStartedAtUnixMs', $data) ? $data['firstStartedAtUnixMs'] : null,
            array_key_exists('terminalAtUnixMs', $data) ? $data['terminalAtUnixMs'] : null,
            array_key_exists('approvalMs', $data) ? $data['approvalMs'] : null,
            array_key_exists('queueMs', $data) ? $data['queueMs'] : null,
            array_key_exists('executionMs', $data) ? $data['executionMs'] : null,
            array_key_exists('endToEndMs', $data) ? $data['endToEndMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
