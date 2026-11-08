<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A server message on `toolgate.triggers.v1`. subscribed: subscription. event: subscription (absent for watch), cursor, event. gap: subscription, fromCursor, toCursor, reason RetentionExpired. revoked: subscription, reason. error: error. */
final readonly class TriggerStreamServerMessage implements \JsonSerializable {
    public function __construct(
        public TriggerStreamServerType $type,
        public ?string $subscription = null,
        public ?string $cursor = null,
        public ?TriggerViewEvent $event = null,
        public ?string $fromCursor = null,
        public ?string $toCursor = null,
        public ?string $reason = null,
        public ?TriggerStreamError $error = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['type', 'subscription', 'cursor', 'event', 'fromCursor', 'toCursor', 'reason', 'error']) || array_diff(['type'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerStreamServerType::from($data['type']),
            array_key_exists('subscription', $data) ? $data['subscription'] : null,
            array_key_exists('cursor', $data) ? $data['cursor'] : null,
            array_key_exists('event', $data) ? TriggerViewEvent::fromArray($data['event']) : null,
            array_key_exists('fromCursor', $data) ? $data['fromCursor'] : null,
            array_key_exists('toCursor', $data) ? $data['toCursor'] : null,
            array_key_exists('reason', $data) ? $data['reason'] : null,
            array_key_exists('error', $data) ? TriggerStreamError::fromArray($data['error']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
