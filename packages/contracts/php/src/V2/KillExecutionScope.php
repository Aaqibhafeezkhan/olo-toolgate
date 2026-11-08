<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Immutable per-attempt Control facts returned only after online permit consumption. Absent facts are unknown and match scoped kills conservatively; empty pool/image means known not applicable. */
final readonly class KillExecutionScope implements \JsonSerializable {
    public function __construct(
        public string $packageDigest,
        public string $toolId,
        public ?string $packageId = null,
        public ?string $publisher = null,
        public ?string $poolId = null,
        public ?string $imageDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'packageDigest', 'publisher', 'toolId', 'poolId', 'imageDigest']) || array_diff(['packageDigest', 'toolId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageDigest'],
            $data['toolId'],
            array_key_exists('packageId', $data) ? $data['packageId'] : null,
            array_key_exists('publisher', $data) ? $data['publisher'] : null,
            array_key_exists('poolId', $data) ? $data['poolId'] : null,
            array_key_exists('imageDigest', $data) ? $data['imageDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
