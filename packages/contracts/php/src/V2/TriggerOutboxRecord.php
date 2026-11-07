<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One outbox record sent to Control, oldest unacknowledged first. */
final readonly class TriggerOutboxRecord implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public TriggerCloudEvent $event
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'event']) || array_diff(['instanceId', 'event'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            TriggerCloudEvent::fromArray($data['event'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
