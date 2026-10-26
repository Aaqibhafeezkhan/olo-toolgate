<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Admission decisions in one bucket. */
final readonly class InsightsAdmissionPoint implements \JsonSerializable {
    public function __construct(
        public int $bucketStartUnixMs,
        public InsightsAdmissionCategory $category,
        public int $count
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['bucketStartUnixMs', 'category', 'count']) || array_diff(['bucketStartUnixMs', 'category', 'count'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['bucketStartUnixMs'],
            InsightsAdmissionCategory::from($data['category']),
            $data['count']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
