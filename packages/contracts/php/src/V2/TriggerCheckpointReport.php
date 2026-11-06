<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Checkpoint metadata changed since the last ack; the body is inline only up to 4 KiB (plan 8.6). */
final readonly class TriggerCheckpointReport implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public int $leaseEpoch,
        public int $afterOutboxSeq,
        public TriggerCheckpointLevel $level,
        public int $version,
        public string $digest,
        public int $sizeBytes,
        public ?string $inline = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'leaseEpoch', 'afterOutboxSeq', 'level', 'version', 'digest', 'sizeBytes', 'inline']) || array_diff(['instanceId', 'leaseEpoch', 'afterOutboxSeq', 'level', 'version', 'digest', 'sizeBytes'], array_keys($data))) {
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
            array_key_exists('inline', $data) ? $data['inline'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
