<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One bucket and outcome. histogram holds counts per InsightsSeries.histogramBoundsMs bucket (last is overflow). */
final readonly class InsightsSeriesPoint implements \JsonSerializable {
    public function __construct(
        public int $bucketStartUnixMs,
        public string $key,
        public InsightsOutcome $outcome,
        public int $count,
        public int $durationCount,
        public int $durationSumMs,
        public array $histogram
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['bucketStartUnixMs', 'key', 'outcome', 'count', 'durationCount', 'durationSumMs', 'histogram']) || array_diff(['bucketStartUnixMs', 'key', 'outcome', 'count', 'durationCount', 'durationSumMs', 'histogram'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['bucketStartUnixMs'],
            $data['key'],
            InsightsOutcome::from($data['outcome']),
            $data['count'],
            $data['durationCount'],
            $data['durationSumMs'],
            array_map(static fn ($item) => $item, $data['histogram'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
