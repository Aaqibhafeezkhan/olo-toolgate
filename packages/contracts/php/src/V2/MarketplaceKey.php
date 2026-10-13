<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A Marketplace release signing key Control trusts, by id and SHA-256 fingerprint of its raw Ed25519 public key. */
final readonly class MarketplaceKey implements \JsonSerializable {
    public function __construct(
        public string $keyId,
        public string $fingerprint
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['keyId', 'fingerprint']) || array_diff(['keyId', 'fingerprint'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['keyId'],
            $data['fingerprint']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
