<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One record Control stored for an instance (plan 6.4): the event as the executor sent it, the producer Control attached from the certificate, the lease and the signed package, and when Control received it. */
final readonly class TriggerStoredEvent implements \JsonSerializable {
    public function __construct(
        public TriggerCloudEvent $event,
        public TriggerProducer $producer,
        public int $receivedAtUnixMs,
        public ?TriggerDispositionView $disposition = null,
        public ?TriggerClassification $classification = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['event', 'producer', 'receivedAtUnixMs', 'disposition', 'classification']) || array_diff(['event', 'producer', 'receivedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerCloudEvent::fromArray($data['event']),
            TriggerProducer::fromArray($data['producer']),
            $data['receivedAtUnixMs'],
            array_key_exists('disposition', $data) ? TriggerDispositionView::fromArray($data['disposition']) : null,
            array_key_exists('classification', $data) ? TriggerClassification::from($data['classification']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
