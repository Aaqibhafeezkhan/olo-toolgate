<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Executor snapshot entry for one held instance (plan 8.2). */
final readonly class TriggerInstanceReport implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public int $configRevision,
        public TriggerPhase $phase,
        public array $conditions,
        public TriggerCounters $counters,
        public TriggerOutboxStatus $outbox,
        public ?int $leaseEpoch = null,
        public ?string $lastEventAt = null,
        public ?string $lastReconcileAt = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'configRevision', 'leaseEpoch', 'phase', 'conditions', 'lastEventAt', 'lastReconcileAt', 'counters', 'outbox']) || array_diff(['instanceId', 'configRevision', 'phase', 'conditions', 'counters', 'outbox'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['configRevision'],
            TriggerPhase::from($data['phase']),
            array_map(static fn ($item) => TriggerCondition::fromArray($item), $data['conditions']),
            TriggerCounters::fromArray($data['counters']),
            TriggerOutboxStatus::fromArray($data['outbox']),
            array_key_exists('leaseEpoch', $data) ? $data['leaseEpoch'] : null,
            array_key_exists('lastEventAt', $data) ? $data['lastEventAt'] : null,
            array_key_exists('lastReconcileAt', $data) ? $data['lastReconcileAt'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
