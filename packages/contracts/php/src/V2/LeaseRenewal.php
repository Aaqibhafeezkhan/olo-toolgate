<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Heartbeat response: the renewed lease, whether cancellation was requested, and any task input waiting for the tool (invocation-state-v2.md T6–T8). */
final readonly class LeaseRenewal implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public int $leaseExpiresAtUnixMs,
        public bool $cancelRequested,
        public mixed $inputResponses = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'leaseExpiresAtUnixMs', 'cancelRequested', 'inputResponses']) || array_diff(['invocationId', 'fencingToken', 'leaseExpiresAtUnixMs', 'cancelRequested'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            $data['leaseExpiresAtUnixMs'],
            $data['cancelRequested'],
            array_key_exists('inputResponses', $data) ? $data['inputResponses'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
