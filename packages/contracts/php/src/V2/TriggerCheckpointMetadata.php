<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Committed checkpoint of one instance. */
final readonly class TriggerCheckpointMetadata implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public int $leaseEpoch,
        public int $afterOutboxSeq,
        public TriggerCheckpointLevel $level,
        public int $version,
        public string $digest,
        public int $sizeBytes,
        public int $chunkCount
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'leaseEpoch', 'afterOutboxSeq', 'level', 'version', 'digest', 'sizeBytes', 'chunkCount']) || array_diff(['instanceId', 'leaseEpoch', 'afterOutboxSeq', 'level', 'version', 'digest', 'sizeBytes', 'chunkCount'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['leaseEpoch'],
            $data['afterOutboxSeq'],
            TriggerCheckpointLevel::from($data['level']),
            $data['version'],
            $data['digest'],
            $data['sizeBytes'],
            $data['chunkCount']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
