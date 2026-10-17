<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool resource requests; each at most the platform ceiling (platform-requirements.md §13.5). */
final readonly class ToolLimits implements \JsonSerializable {
    public function __construct(
        public ?int $memoryBytes = null,
        public ?int $maxInputBytes = null,
        public ?int $maxOutputBytes = null,
        public ?int $maxArgumentProperties = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['memoryBytes', 'maxInputBytes', 'maxOutputBytes', 'maxArgumentProperties']) || array_diff([], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_key_exists('memoryBytes', $data) ? $data['memoryBytes'] : null,
            array_key_exists('maxInputBytes', $data) ? $data['maxInputBytes'] : null,
            array_key_exists('maxOutputBytes', $data) ? $data['maxOutputBytes'] : null,
            array_key_exists('maxArgumentProperties', $data) ? $data['maxArgumentProperties'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
