<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A client message on `toolgate.triggers.v1`. subscribe: subscription, optional resumeAfter. watch: sources, optional filter. ack: subscription, cursor. credit: n. unsubscribe: subscription. */
final readonly class TriggerStreamClientMessage implements \JsonSerializable {
    public function __construct(
        public TriggerStreamClientType $type,
        public ?string $subscription = null,
        public ?string $resumeAfter = null,
        public ?TriggerSubscriptionSources $sources = null,
        public ?string $filter = null,
        public ?string $cursor = null,
        public ?int $n = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['type', 'subscription', 'resumeAfter', 'sources', 'filter', 'cursor', 'n']) || array_diff(['type'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerStreamClientType::from($data['type']),
            array_key_exists('subscription', $data) ? $data['subscription'] : null,
            array_key_exists('resumeAfter', $data) ? $data['resumeAfter'] : null,
            array_key_exists('sources', $data) ? TriggerSubscriptionSources::fromArray($data['sources']) : null,
            array_key_exists('filter', $data) ? $data['filter'] : null,
            array_key_exists('cursor', $data) ? $data['cursor'] : null,
            array_key_exists('n', $data) ? $data['n'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
