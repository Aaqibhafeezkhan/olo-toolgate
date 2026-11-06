<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Trigger block of every executor check-in (plan 8.2, 8.5). */
final readonly class TriggerSync implements \JsonSerializable {
    public function __construct(
        public int $observedGeneration,
        public int $pending,
        public ?TriggerSnapshotPage $snapshot = null,
        public ?array $checkpoints = null,
        public ?array $events = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['observedGeneration', 'snapshot', 'checkpoints', 'events', 'pending']) || array_diff(['observedGeneration', 'pending'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['observedGeneration'],
            $data['pending'],
            array_key_exists('snapshot', $data) ? TriggerSnapshotPage::fromArray($data['snapshot']) : null,
            array_key_exists('checkpoints', $data) ? array_map(static fn ($item) => TriggerCheckpointReport::fromArray($item), $data['checkpoints']) : null,
            array_key_exists('events', $data) ? array_map(static fn ($item) => TriggerOutboxRecord::fromArray($item), $data['events']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
