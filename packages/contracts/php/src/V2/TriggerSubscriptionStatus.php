<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Subscription status. */
final readonly class TriggerSubscriptionStatus implements \JsonSerializable {
    public function __construct(
        public TriggerSubscriptionPhase $phase,
        public TriggerClassification $clearance,
        public ?string $reason = null,
        public ?string $ackedCursor = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['phase', 'reason', 'clearance', 'ackedCursor']) || array_diff(['phase', 'clearance'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerSubscriptionPhase::from($data['phase']),
            TriggerClassification::from($data['clearance']),
            array_key_exists('reason', $data) ? $data['reason'] : null,
            array_key_exists('ackedCursor', $data) ? $data['ackedCursor'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
