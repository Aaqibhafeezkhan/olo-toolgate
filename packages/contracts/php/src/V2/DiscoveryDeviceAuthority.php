<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tenant device CA. keyId is SHA-256 of certificate DER; its subject binds the same tenant UUID. It cannot reuse a discovery signing key. */
final readonly class DiscoveryDeviceAuthority implements \JsonSerializable {
    public function __construct(
        public string $keyId,
        public DiscoveryAuthorityState $state,
        public string $certificatePem
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['keyId', 'state', 'certificatePem']) || array_diff(['keyId', 'state', 'certificatePem'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['keyId'],
            DiscoveryAuthorityState::from($data['state']),
            $data['certificatePem']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
