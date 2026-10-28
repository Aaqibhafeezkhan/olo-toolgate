<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One retained transition fact, for the stage timeline. */
final readonly class InsightsTransition implements \JsonSerializable {
    public function __construct(
        public int $factSeq,
        public string $toState,
        public int $occurredAtUnixMs,
        public ?string $fromState = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['factSeq', 'fromState', 'toState', 'occurredAtUnixMs']) || array_diff(['factSeq', 'toState', 'occurredAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['factSeq'],
            $data['toState'],
            $data['occurredAtUnixMs'],
            array_key_exists('fromState', $data) ? $data['fromState'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
