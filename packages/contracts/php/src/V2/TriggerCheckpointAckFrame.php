<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Barrier advanced to that checkpoint at that level. */
final readonly class TriggerCheckpointAckFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public int $checkpointId,
        public TriggerCheckpointLevel $level
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'checkpointId', 'level']) || array_diff(['kind', 'checkpointId', 'level'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'CHECKPOINT_ACK') {
            throw new \InvalidArgumentException('kind must be CHECKPOINT_ACK');
        }
        return new self(
            $data['kind'],
            $data['checkpointId'],
            TriggerCheckpointLevel::from($data['level'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
