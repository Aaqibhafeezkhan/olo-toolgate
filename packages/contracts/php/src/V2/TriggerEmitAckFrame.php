<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Completes or fails one emit() future. */
final readonly class TriggerEmitAckFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public int $emitSeq,
        public TriggerEmitStatus $status,
        public ?string $reason = null,
        public ?int $outboxSeq = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'emitSeq', 'status', 'reason', 'outboxSeq']) || array_diff(['kind', 'emitSeq', 'status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'EMIT_ACK') {
            throw new \InvalidArgumentException('kind must be EMIT_ACK');
        }
        return new self(
            $data['kind'],
            $data['emitSeq'],
            TriggerEmitStatus::from($data['status']),
            array_key_exists('reason', $data) ? $data['reason'] : null,
            array_key_exists('outboxSeq', $data) ? $data['outboxSeq'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
