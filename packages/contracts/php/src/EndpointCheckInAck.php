<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Endpoint identity foundation wire model. */
final readonly class EndpointCheckInAck implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public int $sequence,
        public int $serverTimeUnixMs,
        public int $nextIntervalMs,
        public ?DeviceIdentity $identity = null,
        public ?EndpointAdoption $adoption = null,
        public ?RemoteToolTask $task = null,
        public ?string $serverName = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'sequence', 'serverTimeUnixMs', 'nextIntervalMs', 'identity', 'adoption', 'task', 'serverName']) || array_diff(['deviceId', 'sequence', 'serverTimeUnixMs', 'nextIntervalMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deviceId'],
            $data['sequence'],
            $data['serverTimeUnixMs'],
            $data['nextIntervalMs'],
            array_key_exists('identity', $data) ? DeviceIdentity::fromArray($data['identity']) : null,
            array_key_exists('adoption', $data) ? EndpointAdoption::fromArray($data['adoption']) : null,
            array_key_exists('task', $data) ? RemoteToolTask::fromArray($data['task']) : null,
            array_key_exists('serverName', $data) ? $data['serverName'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
