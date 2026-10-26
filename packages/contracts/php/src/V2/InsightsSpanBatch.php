<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Spans from one producer. Best-effort: droppedSpans counts what the producer could not keep. */
final readonly class InsightsSpanBatch implements \JsonSerializable {
    public function __construct(
        public array $spans,
        public ?int $droppedSpans = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['spans', 'droppedSpans']) || array_diff(['spans'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => InsightsSpan::fromArray($item), $data['spans']),
            array_key_exists('droppedSpans', $data) ? $data['droppedSpans'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
