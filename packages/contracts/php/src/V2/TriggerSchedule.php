<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** POLL interval or PUSH resync interval. */
final readonly class TriggerSchedule implements \JsonSerializable {
    public function __construct(
        public ?int $intervalMs = null,
        public ?int $resyncIntervalMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['intervalMs', 'resyncIntervalMs']) || array_diff([], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_key_exists('intervalMs', $data) ? $data['intervalMs'] : null,
            array_key_exists('resyncIntervalMs', $data) ? $data['resyncIntervalMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
