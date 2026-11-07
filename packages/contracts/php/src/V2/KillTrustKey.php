<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Organization fleet public key delivered only by the fixed certificate-authenticated Control origin. */
final readonly class KillTrustKey implements \JsonSerializable {
    public function __construct(
        public string $kid,
        public string $n,
        public string $e
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kid', 'n', 'e']) || array_diff(['kid', 'n', 'e'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['e'] !== 'AQAB') {
            throw new \InvalidArgumentException('e must be AQAB');
        }
        return new self(
            $data['kid'],
            $data['n'],
            $data['e']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
