<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Single-owner exact-request authorization. Target consumption is the ordering point for explicitly fenced HTTP targets; unfenced dispatch remains bounded to two seconds. */
final readonly class EffectGrantClaims implements \JsonSerializable {
    public function __construct(
        public string $iss,
        public string $aud,
        public int $iat,
        public int $nbf,
        public int $exp,
        public string $tenantId,
        public string $invocationId,
        public string $fencingToken,
        public int $authorizationEpoch,
        public string $effectId,
        public int $generation,
        public string $authNonce,
        public string $brokerInstanceId,
        public string $handlerId,
        public string $httpRequestDigest,
        public string $profileDigest,
        public string $routeId,
        public EffectClassification $classification,
        public string $executorKeySha256,
        public ?DownstreamEffectKey $downstreamKey = null,
        public ?FencedTarget $fencedTarget = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['iss', 'aud', 'iat', 'nbf', 'exp', 'tenantId', 'invocationId', 'fencingToken', 'authorizationEpoch', 'effectId', 'generation', 'authNonce', 'brokerInstanceId', 'handlerId', 'httpRequestDigest', 'profileDigest', 'routeId', 'classification', 'downstreamKey', 'fencedTarget', 'executorKeySha256']) || array_diff(['iss', 'aud', 'iat', 'nbf', 'exp', 'tenantId', 'invocationId', 'fencingToken', 'authorizationEpoch', 'effectId', 'generation', 'authNonce', 'brokerInstanceId', 'handlerId', 'httpRequestDigest', 'profileDigest', 'routeId', 'classification', 'executorKeySha256'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['iss'],
            $data['aud'],
            $data['iat'],
            $data['nbf'],
            $data['exp'],
            $data['tenantId'],
            $data['invocationId'],
            $data['fencingToken'],
            $data['authorizationEpoch'],
            $data['effectId'],
            $data['generation'],
            $data['authNonce'],
            $data['brokerInstanceId'],
            $data['handlerId'],
            $data['httpRequestDigest'],
            $data['profileDigest'],
            $data['routeId'],
            EffectClassification::from($data['classification']),
            $data['executorKeySha256'],
            array_key_exists('downstreamKey', $data) ? DownstreamEffectKey::fromArray($data['downstreamKey']) : null,
            array_key_exists('fencedTarget', $data) ? FencedTarget::fromArray($data['fencedTarget']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
