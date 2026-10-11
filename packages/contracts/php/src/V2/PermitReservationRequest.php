<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** v2 body of POST /access/invocations/reserve: one permit for one attempt, addressed to one Tool Host or device (invocation-state-v2.md T4). */
final readonly class PermitReservationRequest implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $audience,
        public int $attempt
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'audience', 'attempt']) || array_diff(['invocationId', 'audience', 'attempt'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['audience'],
            $data['attempt']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
