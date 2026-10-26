<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Aggregates for a range. Data before unavailableBeforeUnixMs has expired at this granularity and is absent, not zero. */
final readonly class InsightsSeries implements \JsonSerializable {
    public function __construct(
        public InsightsGranularity $granularity,
        public int $fromUnixMs,
        public int $toUnixMs,
        public string $groupBy,
        public array $histogramBoundsMs,
        public array $points,
        public array $admissions,
        public ?int $unavailableBeforeUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['granularity', 'fromUnixMs', 'toUnixMs', 'groupBy', 'unavailableBeforeUnixMs', 'histogramBoundsMs', 'points', 'admissions']) || array_diff(['granularity', 'fromUnixMs', 'toUnixMs', 'groupBy', 'histogramBoundsMs', 'points', 'admissions'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            InsightsGranularity::from($data['granularity']),
            $data['fromUnixMs'],
            $data['toUnixMs'],
            $data['groupBy'],
            array_map(static fn ($item) => $item, $data['histogramBoundsMs']),
            array_map(static fn ($item) => InsightsSeriesPoint::fromArray($item), $data['points']),
            array_map(static fn ($item) => InsightsAdmissionPoint::fromArray($item), $data['admissions']),
            array_key_exists('unavailableBeforeUnixMs', $data) ? $data['unavailableBeforeUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
