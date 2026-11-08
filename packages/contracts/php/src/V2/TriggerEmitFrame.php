<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One event; the supervisor wraps it in the CloudEvents envelope. */
final readonly class TriggerEmitFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public int $emitSeq,
        public mixed $data,
        public ?string $id = null,
        public ?string $subject = null,
        public ?string $time = null,
        public ?string $position = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'emitSeq', 'id', 'subject', 'time', 'data', 'position']) || array_diff(['kind', 'emitSeq', 'data'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'EMIT') {
            throw new \InvalidArgumentException('kind must be EMIT');
        }
        return new self(
            $data['kind'],
            $data['emitSeq'],
            $data['data'],
            array_key_exists('id', $data) ? $data['id'] : null,
            array_key_exists('subject', $data) ? $data['subject'] : null,
            array_key_exists('time', $data) ? $data['time'] : null,
            array_key_exists('position', $data) ? $data['position'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v, $k) => $v !== null || in_array($k, ['data'], true), ARRAY_FILTER_USE_BOTH);
    }
}
