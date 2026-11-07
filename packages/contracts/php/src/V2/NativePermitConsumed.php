<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Current native endpoint consumption: accepted invocation, live lease and frozen kill facts from one Control transaction. Not a Tool Host admission response. */
final readonly class NativePermitConsumed implements \JsonSerializable {
    public function __construct(
        public \OloLabs\ToolGate\Contracts\EnterpriseInvocation $invocation,
        public string $fencingToken,
        public int $leaseExpiresAtUnixMs,
        public int $authorizationEpoch,
        public KillExecutionScope $killScope
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocation', 'fencingToken', 'leaseExpiresAtUnixMs', 'authorizationEpoch', 'killScope']) || array_diff(['invocation', 'fencingToken', 'leaseExpiresAtUnixMs', 'authorizationEpoch', 'killScope'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            \OloLabs\ToolGate\Contracts\EnterpriseInvocation::fromArray($data['invocation']),
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
