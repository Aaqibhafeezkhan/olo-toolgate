<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Run lease for one instance (plan 7). It authorizes running and reporting only. */
final readonly class TriggerLease implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public int $leaseEpoch,
        public string $leaseToken,
        public int $expiresAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'leaseEpoch', 'leaseToken', 'expiresAtUnixMs']) || array_diff(['instanceId', 'leaseEpoch', 'leaseToken', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['leaseEpoch'],
            $data['leaseToken'],
            $data['expiresAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
