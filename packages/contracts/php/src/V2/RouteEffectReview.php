<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Review stamp created by Control, never accepted from package or worker input. */
final readonly class RouteEffectReview implements \JsonSerializable {
    public function __construct(
        public RouteEffectPolicy $policy,
        public string $reviewedBy,
        public int $reviewedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['policy', 'reviewedBy', 'reviewedAtUnixMs']) || array_diff(['policy', 'reviewedBy', 'reviewedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            RouteEffectPolicy::fromArray($data['policy']),
            $data['reviewedBy'],
            $data['reviewedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
