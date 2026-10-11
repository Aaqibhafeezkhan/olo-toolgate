<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Authenticated Gateway context v2. Carries no device: server-side agents have none (decision 7). */
final readonly class RequestContext implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public string $tenantId,
        public RequestMode $mode,
        public CredentialKind $credentialKind,
        public array $chain,
        public ?string $issuer = null,
        public ?string $userId = null,
        public ?string $agentId = null,
        public ?string $workloadBindingId = null,
        public ?int $sessionEpoch = null,
        public ?int $credentialEpoch = null,
        public ?int $delegatedSessionEpoch = null,
        public ?int $expiresAtUnixMs = null,
        public ?string $bindingId = null,
        public ?string $credentialSha256 = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'tenantId', 'mode', 'credentialKind', 'issuer', 'userId', 'agentId', 'workloadBindingId', 'chain', 'sessionEpoch', 'credentialEpoch', 'delegatedSessionEpoch', 'expiresAtUnixMs', 'bindingId', 'credentialSha256']) || array_diff(['requestId', 'tenantId', 'mode', 'credentialKind', 'chain'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            $data['tenantId'],
            RequestMode::from($data['mode']),
            CredentialKind::from($data['credentialKind']),
            array_map(static fn ($item) => ActorHop::fromArray($item), $data['chain']),
            array_key_exists('issuer', $data) ? $data['issuer'] : null,
            array_key_exists('userId', $data) ? $data['userId'] : null,
            array_key_exists('agentId', $data) ? $data['agentId'] : null,
            array_key_exists('workloadBindingId', $data) ? $data['workloadBindingId'] : null,
            array_key_exists('sessionEpoch', $data) ? $data['sessionEpoch'] : null,
            array_key_exists('credentialEpoch', $data) ? $data['credentialEpoch'] : null,
            array_key_exists('delegatedSessionEpoch', $data) ? $data['delegatedSessionEpoch'] : null,
            array_key_exists('expiresAtUnixMs', $data) ? $data['expiresAtUnixMs'] : null,
            array_key_exists('bindingId', $data) ? $data['bindingId'] : null,
            array_key_exists('credentialSha256', $data) ? $data['credentialSha256'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
