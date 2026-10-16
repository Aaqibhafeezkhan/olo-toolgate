<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Local tool boundary for signed managed registrations; validate schema before use. */
final readonly class LocalToolIpcRequest implements \JsonSerializable {
    public function __construct(
        public int $protocolVersion,
        public string $requestId,
        public LocalToolOperation $operation,
        public ?LocalToolCall $invocation = null,
        public ?string $agentId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['protocolVersion', 'requestId', 'operation', 'invocation', 'agentId']) || array_diff(['protocolVersion', 'requestId', 'operation'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['protocolVersion'],
            $data['requestId'],
            LocalToolOperation::from($data['operation']),
            array_key_exists('invocation', $data) ? LocalToolCall::fromArray($data['invocation']) : null,
            array_key_exists('agentId', $data) ? $data['agentId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
