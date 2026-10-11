<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Immutable kill event. */
final readonly class KillEvent implements \JsonSerializable {
    public function __construct(
        public string $eventId,
        public KillIssuer $issuer,
        public KillScope $scope,
        public KillMode $mode,
        public string $reason,
        public int $issuedAtUnixMs,
        public string $signature
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['eventId', 'issuer', 'scope', 'mode', 'reason', 'issuedAtUnixMs', 'signature']) || array_diff(['eventId', 'issuer', 'scope', 'mode', 'reason', 'issuedAtUnixMs', 'signature'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['eventId'],
            KillIssuer::from($data['issuer']),
            KillScope::fromArray($data['scope']),
            KillMode::from($data['mode']),
            $data['reason'],
            $data['issuedAtUnixMs'],
            $data['signature']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
