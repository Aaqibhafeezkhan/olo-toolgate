<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool configuration review binds the exact declaration to an immutable profile/route. Direct or self-reviewed metadata is insufficient. */
final readonly class RouteDedupeConfiguration implements \JsonSerializable {
    public function __construct(
        public string $profileDigest,
        public string $routeId,
        public TargetDedupeGuarantee $guarantee
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileDigest', 'routeId', 'guarantee']) || array_diff(['profileDigest', 'routeId', 'guarantee'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['profileDigest'],
            $data['routeId'],
            TargetDedupeGuarantee::fromArray($data['guarantee'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
