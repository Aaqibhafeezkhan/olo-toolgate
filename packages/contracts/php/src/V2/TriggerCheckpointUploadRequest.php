<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Start a checkpoint upload; idempotent on (instanceId, version). */
final readonly class TriggerCheckpointUploadRequest implements \JsonSerializable {
    public function __construct(
        public int $version,
        public int $leaseEpoch,
        public int $afterOutboxSeq,
        public TriggerCheckpointLevel $level,
        public int $sizeBytes,
        public string $digest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['version', 'leaseEpoch', 'afterOutboxSeq', 'level', 'sizeBytes', 'digest']) || array_diff(['version', 'leaseEpoch', 'afterOutboxSeq', 'level', 'sizeBytes', 'digest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['version'],
            $data['leaseEpoch'],
            $data['afterOutboxSeq'],
            TriggerCheckpointLevel::from($data['level']),
            $data['sizeBytes'],
            $data['digest']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
