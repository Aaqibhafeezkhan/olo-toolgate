<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Control-issued exact-request authorization for a downstream service; times are Unix milliseconds. */
final readonly class ServicePermitClaims implements \JsonSerializable {
    public function __construct(
        public string $iss,
        public ServiceAudience $aud,
        public string $tenantId,
        public string $sub,
        public \OloLabs\ToolGate\Contracts\V2\RequestMode $mode,
        public string $operation,
        public array $resources,
        public string $requestFingerprint,
        public string $executorKeySha256,
        public string $invocationId,
        public string $nonce,
        public int $authorizationEpoch,
        public int $iat,
        public int $exp,
        public string $executionNonce,
        public ?string $act = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['iss', 'aud', 'tenantId', 'sub', 'act', 'mode', 'operation', 'resources', 'requestFingerprint', 'executorKeySha256', 'invocationId', 'nonce', 'authorizationEpoch', 'iat', 'exp', 'executionNonce']) || array_diff(['iss', 'aud', 'tenantId', 'sub', 'mode', 'operation', 'resources', 'requestFingerprint', 'executorKeySha256', 'invocationId', 'nonce', 'authorizationEpoch', 'iat', 'exp', 'executionNonce'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['iss'],
            ServiceAudience::from($data['aud']),
            $data['tenantId'],
            $data['sub'],
            \OloLabs\ToolGate\Contracts\V2\RequestMode::from($data['mode']),
            $data['operation'],
            array_map(static fn ($item) => ResourceDescriptor::fromArray($item), $data['resources']),
            $data['requestFingerprint'],
            $data['executorKeySha256'],
            $data['invocationId'],
            $data['nonce'],
            $data['authorizationEpoch'],
            $data['iat'],
            $data['exp'],
            $data['executionNonce'],
            array_key_exists('act', $data) ? $data['act'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
