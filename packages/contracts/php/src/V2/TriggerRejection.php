<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Drop the outbox entries of this lease epoch, stop the instance and resync. Records of other epochs are unaffected. */
final readonly class TriggerRejection implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public int $leaseEpoch,
        public TriggerRejectionReason $reason
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'leaseEpoch', 'reason']) || array_diff(['instanceId', 'leaseEpoch', 'reason'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['leaseEpoch'],
            TriggerRejectionReason::from($data['reason'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
