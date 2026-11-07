<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One page of a frozen executor snapshot (plan 8.5). Pages from different generations are never combined. */
final readonly class TriggerSnapshotPage implements \JsonSerializable {
    public function __construct(
        public int $snapshotGeneration,
        public string $snapshotDigest,
        public int $page,
        public int $pageCount,
        public string $pageDigest,
        public array $instances
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['snapshotGeneration', 'snapshotDigest', 'page', 'pageCount', 'pageDigest', 'instances']) || array_diff(['snapshotGeneration', 'snapshotDigest', 'page', 'pageCount', 'pageDigest', 'instances'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['snapshotGeneration'],
            $data['snapshotDigest'],
            $data['page'],
            $data['pageCount'],
            $data['pageDigest'],
            array_map(static fn ($item) => TriggerInstanceReport::fromArray($item), $data['instances'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
