<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Successful consumption with current authorization epoch and immutable per-attempt revocation scope. */
final readonly class PermitConsumed implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public int $leaseExpiresAtUnixMs,
        public int $authorizationEpoch,
        public KillExecutionScope $killScope
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'leaseExpiresAtUnixMs', 'authorizationEpoch', 'killScope']) || array_diff(['invocationId', 'fencingToken', 'leaseExpiresAtUnixMs', 'authorizationEpoch', 'killScope'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            $data['leaseExpiresAtUnixMs'],
            $data['authorizationEpoch'],
            KillExecutionScope::fromArray($data['killScope'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
