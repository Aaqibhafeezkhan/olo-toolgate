<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Read first by the operator page: what is collected and how current it is. */
final readonly class InsightsStatus implements \JsonSerializable {
    public function __construct(
        public InsightsMode $mode,
        public int $generatedAtUnixMs,
        public int $tenants,
        public int $unprojectedFacts,
        public int $summariesSuspendedTenants,
        public int $failedTenants,
        public array $sourceGaps,
        public ?int $oldestUnprojectedUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['mode', 'generatedAtUnixMs', 'tenants', 'unprojectedFacts', 'oldestUnprojectedUnixMs', 'summariesSuspendedTenants', 'failedTenants', 'sourceGaps']) || array_diff(['mode', 'generatedAtUnixMs', 'tenants', 'unprojectedFacts', 'summariesSuspendedTenants', 'failedTenants', 'sourceGaps'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            InsightsMode::from($data['mode']),
            $data['generatedAtUnixMs'],
            $data['tenants'],
            $data['unprojectedFacts'],
            $data['summariesSuspendedTenants'],
            $data['failedTenants'],
            array_map(static fn ($item) => InsightsSourceGap::fromArray($item), $data['sourceGaps']),
            array_key_exists('oldestUnprojectedUnixMs', $data) ? $data['oldestUnprojectedUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
