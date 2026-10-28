<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Exact authenticated invocation with original arguments and installed code digests. */
final readonly class EnterpriseInvocationRequest implements \JsonSerializable {
    public function __construct(
        public \OloLabs\ToolGate\Contracts\V2\RequestContext $context,
        public AuthorizationRequest $request,
        public string $toolDigest,
        public string $packageDigest,
        public ?string $downstreamIdempotencyKey = null,
        public ?\OloLabs\ToolGate\Contracts\V2\ExecutionContext $executionContext = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['context', 'request', 'toolDigest', 'packageDigest', 'downstreamIdempotencyKey', 'executionContext']) || array_diff(['context', 'request', 'toolDigest', 'packageDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            \OloLabs\ToolGate\Contracts\V2\RequestContext::fromArray($data['context']),
            AuthorizationRequest::fromArray($data['request']),
            $data['toolDigest'],
            $data['packageDigest'],
            array_key_exists('downstreamIdempotencyKey', $data) ? $data['downstreamIdempotencyKey'] : null,
            array_key_exists('executionContext', $data) ? \OloLabs\ToolGate\Contracts\V2\ExecutionContext::fromArray($data['executionContext']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
