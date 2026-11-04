<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Current reviewed contract facts for one write route; no operation-specific retry authority. */
final readonly class RouteRecoveryReadiness implements \JsonSerializable {
    public function __construct(
        public string $routeId,
        public RecoveryReadinessReason $reason,
        public bool $lookupAvailable,
        public ?int $reviewExpiresAtUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['routeId', 'reason', 'lookupAvailable', 'reviewExpiresAtUnixMs']) || array_diff(['routeId', 'reason', 'lookupAvailable'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['routeId'],
            RecoveryReadinessReason::from($data['reason']),
            $data['lookupAvailable'],
            array_key_exists('reviewExpiresAtUnixMs', $data) ? $data['reviewExpiresAtUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
