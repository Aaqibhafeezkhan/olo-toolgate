<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Where an assignment runs (plan 9.1). CLIENT: devices and Device Groups, minus excluded devices. Entries are unique; Control refuses repeats. */
final readonly class TriggerTarget implements \JsonSerializable {
    public function __construct(
        public TriggerExecutor $executor,
        public ?array $devices = null,
        public ?array $deviceGroups = null,
        public ?array $excludeDevices = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['executor', 'devices', 'deviceGroups', 'excludeDevices']) || array_diff(['executor'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerExecutor::from($data['executor']),
            array_key_exists('devices', $data) ? array_map(static fn ($item) => $item, $data['devices']) : null,
            array_key_exists('deviceGroups', $data) ? array_map(static fn ($item) => $item, $data['deviceGroups']) : null,
            array_key_exists('excludeDevices', $data) ? array_map(static fn ($item) => $item, $data['excludeDevices']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
