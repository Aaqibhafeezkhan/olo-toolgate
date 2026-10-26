<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** The detail window: summary, attempts and, while facts are retained, the transitions. */
final readonly class InsightsInvocationDetail implements \JsonSerializable {
    public function __construct(
        public InsightsInvocation $invocation,
        public array $attempts,
        public array $transitions,
        public bool $transitionsRetained
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocation', 'attempts', 'transitions', 'transitionsRetained']) || array_diff(['invocation', 'attempts', 'transitions', 'transitionsRetained'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            InsightsInvocation::fromArray($data['invocation']),
            array_map(static fn ($item) => InsightsAttempt::fromArray($item), $data['attempts']),
            array_map(static fn ($item) => InsightsTransition::fromArray($item), $data['transitions']),
            $data['transitionsRetained']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
