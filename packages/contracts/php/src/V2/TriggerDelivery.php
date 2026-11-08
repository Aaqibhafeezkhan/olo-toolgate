<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One delivery of a routed event to one consumer. */
final readonly class TriggerDelivery implements \JsonSerializable {
    public function __construct(
        public string $deliveryId,
        public TriggerDeliveryConsumer $consumer,
        public string $cursor,
        public string $eventSource,
        public string $eventId,
        public string $eventType,
        public string $assignment,
        public TriggerDeliveryState $state,
        public int $attempts,
        public int $createdAtUnixMs,
        public int $updatedAtUnixMs,
        public ?int $nextAttemptAtUnixMs = null,
        public ?string $reason = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deliveryId', 'consumer', 'cursor', 'eventSource', 'eventId', 'eventType', 'assignment', 'state', 'attempts', 'createdAtUnixMs', 'updatedAtUnixMs', 'nextAttemptAtUnixMs', 'reason']) || array_diff(['deliveryId', 'consumer', 'cursor', 'eventSource', 'eventId', 'eventType', 'assignment', 'state', 'attempts', 'createdAtUnixMs', 'updatedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deliveryId'],
            TriggerDeliveryConsumer::fromArray($data['consumer']),
            $data['cursor'],
            $data['eventSource'],
            $data['eventId'],
            $data['eventType'],
            $data['assignment'],
            TriggerDeliveryState::from($data['state']),
            $data['attempts'],
            $data['createdAtUnixMs'],
            $data['updatedAtUnixMs'],
            array_key_exists('nextAttemptAtUnixMs', $data) ? $data['nextAttemptAtUnixMs'] : null,
            array_key_exists('reason', $data) ? $data['reason'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
