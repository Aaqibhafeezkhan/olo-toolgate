<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Actual JSON compiler features and disposable cache statistics. This is not a readiness or execution grant. */
final readonly class WorkflowGraphCapabilities implements \JsonSerializable {
    public function __construct(
        public int $entries,
        public int $capacity,
        public string $compilerDigest,
        public array $features
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['entries', 'capacity', 'compilerDigest', 'features']) || array_diff(['entries', 'capacity', 'compilerDigest', 'features'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['capacity'] !== 128) {
            throw new \InvalidArgumentException('capacity must be 128');
        }
        return new self(
            $data['entries'],
            $data['capacity'],
            $data['compilerDigest'],
            array_map(static fn ($item) => $item, $data['features'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
