<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Recompute one tenant's aggregate cells for a range from its summaries. dryRun only compares. */
final readonly class InsightsRebuildRequest implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public int $fromUnixMs,
        public int $toUnixMs,
        public bool $dryRun
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'fromUnixMs', 'toUnixMs', 'dryRun']) || array_diff(['tenantId', 'fromUnixMs', 'toUnixMs', 'dryRun'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['fromUnixMs'],
            $data['toUnixMs'],
            $data['dryRun']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
