<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A durable socket subscription (plan 13.1). */
final readonly class TriggerSubscriptionSpec implements \JsonSerializable {
    public function __construct(
        public TriggerSubscriptionSources $sources,
        public int $expiresAtUnixMs,
        public ?string $owner = null,
        public ?string $filter = null,
        public ?bool $includeState = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['owner', 'sources', 'filter', 'includeState', 'expiresAtUnixMs']) || array_diff(['sources', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerSubscriptionSources::fromArray($data['sources']),
            $data['expiresAtUnixMs'],
            array_key_exists('owner', $data) ? $data['owner'] : null,
            array_key_exists('filter', $data) ? $data['filter'] : null,
            array_key_exists('includeState', $data) ? $data['includeState'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
