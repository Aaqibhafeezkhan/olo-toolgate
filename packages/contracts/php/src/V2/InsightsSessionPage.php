<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A tenant's sessions with calls accepted in the range, most recent activity first. next continues the list. */
final readonly class InsightsSessionPage implements \JsonSerializable {
    public function __construct(
        public array $items,
        public int $callsWithoutSession,
        public ?string $next = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['items', 'next', 'callsWithoutSession']) || array_diff(['items', 'callsWithoutSession'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => InsightsSession::fromArray($item), $data['items']),
            $data['callsWithoutSession'],
            array_key_exists('next', $data) ? $data['next'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
