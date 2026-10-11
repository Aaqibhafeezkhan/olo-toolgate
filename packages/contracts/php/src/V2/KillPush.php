<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Push to Tool Hosts and clients for running work. */
final readonly class KillPush implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public int $authorizationEpoch,
        public array $kills,
        public array $recoveries,
        public string $signature
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'authorizationEpoch', 'kills', 'recoveries', 'signature']) || array_diff(['tenantId', 'authorizationEpoch', 'kills', 'recoveries', 'signature'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['authorizationEpoch'],
            array_map(static fn ($item) => KillEvent::fromArray($item), $data['kills']),
            array_map(static fn ($item) => RecoveryEvent::fromArray($item), $data['recoveries']),
            $data['signature']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
