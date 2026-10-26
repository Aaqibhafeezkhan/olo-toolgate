<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Dedicated Gateway-authenticated request for one device-local tool. */
final readonly class RemoteToolSubmission implements \JsonSerializable {
    public function __construct(
        public AuthorizationRequest $request,
        public int $expiresAtUnixMs,
        public \OloLabs\ToolGate\Contracts\V2\RequestContext $context,
        public string $invocationId,
        public ?string $traceparent = null,
        public ?\OloLabs\ToolGate\Contracts\V2\ExecutionContext $executionContext = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['request', 'expiresAtUnixMs', 'context', 'invocationId', 'traceparent', 'executionContext']) || array_diff(['request', 'expiresAtUnixMs', 'context', 'invocationId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            AuthorizationRequest::fromArray($data['request']),
            $data['expiresAtUnixMs'],
            \OloLabs\ToolGate\Contracts\V2\RequestContext::fromArray($data['context']),
            $data['invocationId'],
            array_key_exists('traceparent', $data) ? $data['traceparent'] : null,
            array_key_exists('executionContext', $data) ? \OloLabs\ToolGate\Contracts\V2\ExecutionContext::fromArray($data['executionContext']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
