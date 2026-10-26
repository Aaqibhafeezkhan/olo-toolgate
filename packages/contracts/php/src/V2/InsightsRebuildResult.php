<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** How many cells the recomputation compared and how many differed from the live projection. */
final readonly class InsightsRebuildResult implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public int $fromUnixMs,
        public int $toUnixMs,
        public int $cellsCompared,
        public int $cellsDiffering,
        public bool $applied
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'fromUnixMs', 'toUnixMs', 'cellsCompared', 'cellsDiffering', 'applied']) || array_diff(['tenantId', 'fromUnixMs', 'toUnixMs', 'cellsCompared', 'cellsDiffering', 'applied'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['fromUnixMs'],
            $data['toUnixMs'],
            $data['cellsCompared'],
            $data['cellsDiffering'],
            $data['applied']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
