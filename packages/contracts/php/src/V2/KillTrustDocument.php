<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Direct HTTPS key bootstrap; never accept it from a worker or relayed push. */
final readonly class KillTrustDocument implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public int $authorizationEpoch,
        public array $keys
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'authorizationEpoch', 'keys']) || array_diff(['tenantId', 'authorizationEpoch', 'keys'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['authorizationEpoch'],
            array_map(static fn ($item) => KillTrustKey::fromArray($item), $data['keys'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
