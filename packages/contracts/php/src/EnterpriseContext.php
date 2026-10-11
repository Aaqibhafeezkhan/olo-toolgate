<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Trusted complete runtime identity. Authentication adapters construct it; ordinary arguments cannot change it. deviceId is the device Control placed the call on: the one online device of the execution binding device group (the user's own first), or the device a human chose. */
final readonly class EnterpriseContext implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public string $tenantId,
        public \OloLabs\ToolGate\Contracts\V2\RequestMode $mode,
        public array $chain,
        public string $bindingId,
        public string $deviceId,
        public ?string $userId = null,
        public ?string $agentId = null,
        public ?string $workloadBindingId = null,
        public ?int $sessionEpoch = null,
        public ?int $credentialEpoch = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'tenantId', 'mode', 'userId', 'agentId', 'workloadBindingId', 'chain', 'sessionEpoch', 'credentialEpoch', 'bindingId', 'deviceId']) || array_diff(['requestId', 'tenantId', 'mode', 'chain', 'bindingId', 'deviceId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            $data['tenantId'],
            \OloLabs\ToolGate\Contracts\V2\RequestMode::from($data['mode']),
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\V2\ActorHop::fromArray($item), $data['chain']),
            $data['bindingId'],
            $data['deviceId'],
            array_key_exists('userId', $data) ? $data['userId'] : null,
            array_key_exists('agentId', $data) ? $data['agentId'] : null,
            array_key_exists('workloadBindingId', $data) ? $data['workloadBindingId'] : null,
            array_key_exists('sessionEpoch', $data) ? $data['sessionEpoch'] : null,
            array_key_exists('credentialEpoch', $data) ? $data['credentialEpoch'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
