<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Service profile. Its digest and revision are pinned in the permit. */
final readonly class ServiceProfile implements \JsonSerializable {
    public function __construct(
        public string $profileId,
        public int $revision,
        public string $packageId,
        public array $destinations,
        public array $routes,
        public array $privateRanges
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileId', 'revision', 'packageId', 'destinations', 'routes', 'privateRanges']) || array_diff(['profileId', 'revision', 'packageId', 'destinations', 'routes', 'privateRanges'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['profileId'],
            $data['revision'],
            $data['packageId'],
            array_map(static fn ($item) => ServiceDestination::fromArray($item), $data['destinations']),
            array_map(static fn ($item) => RouteDeclaration::fromArray($item), $data['routes']),
            array_map(static fn ($item) => PrivateRangeApproval::fromArray($item), $data['privateRanges'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
