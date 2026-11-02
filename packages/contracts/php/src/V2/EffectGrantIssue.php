<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Request for fresh owner-bound authority. Control independently verifies the signed argument preimage and reconstructs the reviewed request. */
final readonly class EffectGrantIssue implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public SignedInvocationPermit $invocationPermit,
        public string $brokerInstanceId,
        public string $handlerId,
        public \stdClass $arguments,
        public string $httpRequestDigest,
        public \OloLabs\ToolGate\Contracts\PreparedHttpRequestManifest $httpRequestManifest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'invocationPermit', 'brokerInstanceId', 'handlerId', 'arguments', 'httpRequestDigest', 'httpRequestManifest']) || array_diff(['invocationId', 'fencingToken', 'invocationPermit', 'brokerInstanceId', 'handlerId', 'arguments', 'httpRequestDigest', 'httpRequestManifest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            SignedInvocationPermit::fromArray($data['invocationPermit']),
            $data['brokerInstanceId'],
            $data['handlerId'],
            (object) $data['arguments'],
            $data['httpRequestDigest'],
            \OloLabs\ToolGate\Contracts\PreparedHttpRequestManifest::fromArray($data['httpRequestManifest'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
