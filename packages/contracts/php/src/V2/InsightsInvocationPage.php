<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explorer results, newest first. next continues the search. */
final readonly class InsightsInvocationPage implements \JsonSerializable {
    public function __construct(
        public array $items,
        public ?string $next = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['items', 'next']) || array_diff(['items'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => InsightsInvocation::fromArray($item), $data['items']),
            array_key_exists('next', $data) ? $data['next'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
