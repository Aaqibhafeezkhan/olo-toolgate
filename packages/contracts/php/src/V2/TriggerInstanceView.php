<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Read-only instance resource (plan 9.2). */
final readonly class TriggerInstanceView implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public string $assignment,
        public string $triggerId,
        public TriggerExecutor $executor,
        public string $executorId,
        public string $packageDigest,
        public string $triggerDescriptorDigest,
        public int $configRevision,
        public TriggerDesiredState $desiredState,
        public string $desiredStateSource,
        public TriggerPhase $phase,
        public array $conditions,
        public ?int $leaseEpoch = null,
        public ?int $lastSeenAtUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'assignment', 'triggerId', 'executor', 'executorId', 'packageDigest', 'triggerDescriptorDigest', 'configRevision', 'desiredState', 'desiredStateSource', 'phase', 'conditions', 'leaseEpoch', 'lastSeenAtUnixMs']) || array_diff(['instanceId', 'assignment', 'triggerId', 'executor', 'executorId', 'packageDigest', 'triggerDescriptorDigest', 'configRevision', 'desiredState', 'desiredStateSource', 'phase', 'conditions'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['assignment'],
            $data['triggerId'],
            TriggerExecutor::from($data['executor']),
            $data['executorId'],
            $data['packageDigest'],
            $data['triggerDescriptorDigest'],
            $data['configRevision'],
            TriggerDesiredState::from($data['desiredState']),
            $data['desiredStateSource'],
            TriggerPhase::from($data['phase']),
            array_map(static fn ($item) => TriggerCondition::fromArray($item), $data['conditions']),
            array_key_exists('leaseEpoch', $data) ? $data['leaseEpoch'] : null,
            array_key_exists('lastSeenAtUnixMs', $data) ? $data['lastSeenAtUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
