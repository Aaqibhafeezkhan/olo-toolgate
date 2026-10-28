<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Protected JWS header. Algorithm, message purpose and key selection are authenticated; no unprotected or remote-key headers. */
final readonly class DiscoveryHeader implements \JsonSerializable {
    public function __construct(
        public string $alg,
        public string $typ,
        public string $kid
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['alg', 'typ', 'kid']) || array_diff(['alg', 'typ', 'kid'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['alg'] !== 'RS256') {
            throw new \InvalidArgumentException('alg must be RS256');
        }
        if ($data['typ'] !== 'toolgate-discovery+jws') {
            throw new \InvalidArgumentException('typ must be toolgate-discovery+jws');
        }
        return new self(
            $data['alg'],
            $data['typ'],
            $data['kid']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
