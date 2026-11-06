<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explicit bounded route allowance. Each distinct effect identity reserves one use atomically with its authorization; another generation of the same identity reuses that use. */
final readonly class EffectConsentSlot implements \JsonSerializable {
    public function __construct(
        public int $slotIndex,
        public EffectConsentMappedRequest $request,
        public int $permittedCount
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['slotIndex', 'request', 'permittedCount']) || array_diff(['slotIndex', 'request', 'permittedCount'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['slotIndex'],
            EffectConsentMappedRequest::fromArray($data['request']),
            $data['permittedCount']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
