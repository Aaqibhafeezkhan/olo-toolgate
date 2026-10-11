<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Per-tool limit override; at most the platform ceiling. */
final readonly class LimitOverride implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public ?int $maxDurationMs = null,
        public ?int $maxConcurrency = null,
        public ?int $memoryBytes = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'maxDurationMs', 'maxConcurrency', 'memoryBytes']) || array_diff(['toolId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            array_key_exists('maxDurationMs', $data) ? $data['maxDurationMs'] : null,
            array_key_exists('maxConcurrency', $data) ? $data['maxConcurrency'] : null,
            array_key_exists('memoryBytes', $data) ? $data['memoryBytes'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
