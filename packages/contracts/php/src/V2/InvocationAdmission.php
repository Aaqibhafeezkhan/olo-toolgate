<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Response to a v2 POST /access/invocations for NEW and EXISTING reservations. IDEMPOTENCY_CONFLICT, IDEMPOTENCY_KEY_IN_USE and EXPIRED_KEY are 409 errors with that code and carry nothing else. requestState is present with an ASK outcome for an MRTR client. */
final readonly class InvocationAdmission implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public TaskState $state,
        public ?ReservationOutcome $reservation = null,
        public ?InvocationTask $task = null,
        public ?string $approvalId = null,
        public ?string $approvalUrl = null,
        public ?string $requestState = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'state', 'reservation', 'task', 'approvalId', 'approvalUrl', 'requestState']) || array_diff(['invocationId', 'state'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            TaskState::from($data['state']),
            array_key_exists('reservation', $data) ? ReservationOutcome::from($data['reservation']) : null,
            array_key_exists('task', $data) ? InvocationTask::fromArray($data['task']) : null,
            array_key_exists('approvalId', $data) ? $data['approvalId'] : null,
            array_key_exists('approvalUrl', $data) ? $data['approvalUrl'] : null,
            array_key_exists('requestState', $data) ? $data['requestState'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
