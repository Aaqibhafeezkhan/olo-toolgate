<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Control-side terminal rejection of one record. */
final readonly class TriggerDisposition implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public int $leaseEpoch,
        public int $outboxSeq,
        public string $id,
        public TriggerDispositionKind $disposition,
        public string $reason
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'leaseEpoch', 'outboxSeq', 'id', 'disposition', 'reason']) || array_diff(['instanceId', 'leaseEpoch', 'outboxSeq', 'id', 'disposition', 'reason'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['leaseEpoch'],
            $data['outboxSeq'],
            $data['id'],
            TriggerDispositionKind::from($data['disposition']),
            $data['reason']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
