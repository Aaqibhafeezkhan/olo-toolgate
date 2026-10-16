<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Settings visible to the current actor. Unreadable feature groups are omitted. */
final readonly class ControlSettingsView implements \JsonSerializable {
    public function __construct(
        public int $formatVersion,
        public int $revision,
        public ?GatewaySettings $gateway = null,
        public ?DeviceApproval $deviceApproval = null,
        public ?ClientCheckIn $clientCheckIn = null,
        public ?ConfigurationSettings $configuration = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['formatVersion', 'revision', 'gateway', 'deviceApproval', 'clientCheckIn', 'configuration']) || array_diff(['formatVersion', 'revision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['formatVersion'] !== 2) {
            throw new \InvalidArgumentException('formatVersion must be 2');
        }
        return new self(
            $data['formatVersion'],
            $data['revision'],
            array_key_exists('gateway', $data) ? GatewaySettings::fromArray($data['gateway']) : null,
            array_key_exists('deviceApproval', $data) ? DeviceApproval::fromArray($data['deviceApproval']) : null,
            array_key_exists('clientCheckIn', $data) ? ClientCheckIn::fromArray($data['clientCheckIn']) : null,
            array_key_exists('configuration', $data) ? ConfigurationSettings::fromArray($data['configuration']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
