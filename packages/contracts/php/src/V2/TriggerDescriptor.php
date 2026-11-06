<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Signed semantic descriptor of one trigger export (plan 5.9). */
final readonly class TriggerDescriptor implements \JsonSerializable {
    public function __construct(
        public TriggerKind $kind,
        public string $triggerId,
        public TriggerMode $mode,
        public array $executors,
        public string $eventType,
        public \stdClass $configSchema,
        public \stdClass $eventSchema,
        public int $checkpointSchemaVersion,
        public TriggerSourceReplay $sourceReplay,
        public TriggerSchedule $schedule,
        public TriggerLimits $limits,
        public TriggerOverflow $overflow,
        public TriggerTerminalRejectionPolicy $onTerminalRejection,
        public ?bool $lossTolerant = null,
        public ?TriggerSnapshotCapacity $snapshot = null,
        public ?array $routes = null,
        public ?array $tcpDestinations = null,
        public ?array $secrets = null,
        public ?array $permissions = null,
        public ?array $tiers = null,
        public ?array $selfTests = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'triggerId', 'mode', 'executors', 'eventType', 'lossTolerant', 'configSchema', 'eventSchema', 'checkpointSchemaVersion', 'sourceReplay', 'schedule', 'limits', 'overflow', 'onTerminalRejection', 'snapshot', 'routes', 'tcpDestinations', 'secrets', 'permissions', 'tiers', 'selfTests']) || array_diff(['kind', 'triggerId', 'mode', 'executors', 'eventType', 'configSchema', 'eventSchema', 'checkpointSchemaVersion', 'sourceReplay', 'schedule', 'limits', 'overflow', 'onTerminalRejection'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerKind::from($data['kind']),
            $data['triggerId'],
            TriggerMode::from($data['mode']),
            array_map(static fn ($item) => TriggerExecutor::from($item), $data['executors']),
            $data['eventType'],
            (object) $data['configSchema'],
            (object) $data['eventSchema'],
            $data['checkpointSchemaVersion'],
            TriggerSourceReplay::from($data['sourceReplay']),
            TriggerSchedule::fromArray($data['schedule']),
            TriggerLimits::fromArray($data['limits']),
            TriggerOverflow::from($data['overflow']),
            TriggerTerminalRejectionPolicy::from($data['onTerminalRejection']),
            array_key_exists('lossTolerant', $data) ? $data['lossTolerant'] : null,
            array_key_exists('snapshot', $data) ? TriggerSnapshotCapacity::fromArray($data['snapshot']) : null,
            array_key_exists('routes', $data) ? array_map(static fn ($item) => $item, $data['routes']) : null,
            array_key_exists('tcpDestinations', $data) ? array_map(static fn ($item) => $item, $data['tcpDestinations']) : null,
            array_key_exists('secrets', $data) ? array_map(static fn ($item) => $item, $data['secrets']) : null,
            array_key_exists('permissions', $data) ? array_map(static fn ($item) => $item, $data['permissions']) : null,
            array_key_exists('tiers', $data) ? array_map(static fn ($item) => $item, $data['tiers']) : null,
            array_key_exists('selfTests', $data) ? array_map(static fn ($item) => $item, $data['selfTests']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
