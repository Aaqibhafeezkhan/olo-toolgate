<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Signed webhook delivery envelope. Use subscriptionId to select the signing key, verify timestamp and raw-body signature, then deduplicate by event.eventId. */
final readonly class ApplicationWebhookDelivery implements \JsonSerializable {
    public function __construct(
        public string $subscriptionId,
        public ApplicationEvent $event
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['subscriptionId', 'event']) || array_diff(['subscriptionId', 'event'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['subscriptionId'],
            ApplicationEvent::fromArray($data['event'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
