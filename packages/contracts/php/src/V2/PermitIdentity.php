<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Verified identity and epochs. */
final readonly class PermitIdentity implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public RequestMode $mode,
        public array $chain,
        public int $authorizationEpoch,
        public ?string $agentId = null,
        public ?string $userId = null,
        public ?int $credentialEpoch = null,
        public ?\stdClass $claims = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'mode', 'agentId', 'userId', 'chain', 'credentialEpoch', 'authorizationEpoch', 'claims']) || array_diff(['tenantId', 'mode', 'chain', 'authorizationEpoch'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            RequestMode::from($data['mode']),
            array_map(static fn ($item) => ActorHop::fromArray($item), $data['chain']),
            $data['authorizationEpoch'],
            array_key_exists('agentId', $data) ? $data['agentId'] : null,
            array_key_exists('userId', $data) ? $data['userId'] : null,
            array_key_exists('credentialEpoch', $data) ? $data['credentialEpoch'] : null,
            array_key_exists('claims', $data) ? (object) $data['claims'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
