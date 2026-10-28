<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Public-only RSA JWK, restricted to 3072/4096-bit RS256 keys with exponent 65537. No private parameters or key URLs. */
final readonly class DiscoveryPublicKey implements \JsonSerializable {
    public function __construct(
        public string $kty,
        public string $use,
        public string $alg,
        public string $kid,
        public string $n,
        public string $e
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kty', 'use', 'alg', 'kid', 'n', 'e']) || array_diff(['kty', 'use', 'alg', 'kid', 'n', 'e'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kty'] !== 'RSA') {
            throw new \InvalidArgumentException('kty must be RSA');
        }
        if ($data['use'] !== 'sig') {
            throw new \InvalidArgumentException('use must be sig');
        }
        if ($data['alg'] !== 'RS256') {
            throw new \InvalidArgumentException('alg must be RS256');
        }
        if ($data['e'] !== 'AQAB') {
            throw new \InvalidArgumentException('e must be AQAB');
        }
        return new self(
            $data['kty'],
            $data['use'],
            $data['alg'],
            $data['kid'],
            $data['n'],
            $data['e']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
