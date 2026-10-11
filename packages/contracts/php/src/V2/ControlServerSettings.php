<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Settings record. revision is compared with If-Match. */
final readonly class ControlServerSettings implements \JsonSerializable {
    public function __construct(
        public int $formatVersion,
        public int $revision,
        public GatewaySettings $gateway,
        public DeviceApproval $deviceApproval,
        public ClientCheckIn $clientCheckIn
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['formatVersion', 'revision', 'gateway', 'deviceApproval', 'clientCheckIn']) || array_diff(['formatVersion', 'revision', 'gateway', 'deviceApproval', 'clientCheckIn'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['formatVersion'] !== 2) {
            throw new \InvalidArgumentException('formatVersion must be 2');
        }
        return new self(
            $data['formatVersion'],
            $data['revision'],
            GatewaySettings::fromArray($data['gateway']),
            DeviceApproval::fromArray($data['deviceApproval']),
            ClientCheckIn::fromArray($data['clientCheckIn'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
