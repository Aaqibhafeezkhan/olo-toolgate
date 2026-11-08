<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explicit checkpoint; subject to the same barrier as position. */
final readonly class TriggerCheckpointFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public int $checkpointId,
        public int $afterEmitSeq,
        public string $state
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'checkpointId', 'afterEmitSeq', 'state']) || array_diff(['kind', 'checkpointId', 'afterEmitSeq', 'state'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'CHECKPOINT') {
            throw new \InvalidArgumentException('kind must be CHECKPOINT');
        }
        return new self(
            $data['kind'],
            $data['checkpointId'],
            $data['afterEmitSeq'],
            $data['state']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
