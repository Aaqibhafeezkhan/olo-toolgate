<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Bounded lease delivered only over device-authenticated polling; executable definitions are never accepted from an agent. */
final readonly class RemoteToolTask implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public string $leaseId,
        public AuthorizationRequest $request,
        public int $expiresAtUnixMs,
        public EnterpriseInvocation $invocation,
        public EnterpriseSignedPermit $permit,
        public ?string $traceparent = null,
        public ?\OloLabs\ToolGate\Contracts\V2\ExecutionContext $executionContext = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'leaseId', 'request', 'expiresAtUnixMs', 'invocation', 'permit', 'traceparent', 'executionContext']) || array_diff(['requestId', 'leaseId', 'request', 'expiresAtUnixMs', 'invocation', 'permit'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            $data['leaseId'],
            AuthorizationRequest::fromArray($data['request']),
            $data['expiresAtUnixMs'],
            EnterpriseInvocation::fromArray($data['invocation']),
            EnterpriseSignedPermit::fromArray($data['permit']),
            array_key_exists('traceparent', $data) ? $data['traceparent'] : null,
            array_key_exists('executionContext', $data) ? \OloLabs\ToolGate\Contracts\V2\ExecutionContext::fromArray($data['executionContext']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
