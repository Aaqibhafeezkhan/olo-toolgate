<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Correlation context of one execution attempt (ADR 031). executionId and attemptNumber are set by the executor from its permit; the other members are declared by the caller, are never trusted and never affect authorization, routing or policy. */
final readonly class ExecutionContext implements \JsonSerializable {
    public function __construct(
        public ?string $executionId = null,
        public ?int $attemptNumber = null,
        public ?string $toolCallId = null,
        public ?string $sessionId = null,
        public ?string $agentRunId = null,
        public ?string $parentInvocationId = null,
        public ?string $tracestate = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['executionId', 'attemptNumber', 'toolCallId', 'sessionId', 'agentRunId', 'parentInvocationId', 'tracestate']) || array_diff([], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_key_exists('executionId', $data) ? $data['executionId'] : null,
            array_key_exists('attemptNumber', $data) ? $data['attemptNumber'] : null,
            array_key_exists('toolCallId', $data) ? $data['toolCallId'] : null,
            array_key_exists('sessionId', $data) ? $data['sessionId'] : null,
            array_key_exists('agentRunId', $data) ? $data['agentRunId'] : null,
            array_key_exists('parentInvocationId', $data) ? $data['parentInvocationId'] : null,
            array_key_exists('tracestate', $data) ? $data['tracestate'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
