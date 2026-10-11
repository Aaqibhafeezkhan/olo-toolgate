<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Reservation result. IDEMPOTENCY_KEY_IN_USE carries nothing else. */
final readonly class BusinessKeyReservationResult implements \JsonSerializable {
    public function __construct(
        public ReservationOutcome $outcome,
        public ?string $invocationId = null,
        public ?TaskState $state = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['outcome', 'invocationId', 'state']) || array_diff(['outcome'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ReservationOutcome::from($data['outcome']),
            array_key_exists('invocationId', $data) ? $data['invocationId'] : null,
            array_key_exists('state', $data) ? TaskState::from($data['state']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
