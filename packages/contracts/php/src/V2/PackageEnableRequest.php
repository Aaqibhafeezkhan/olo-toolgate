<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Enable exactly the reviewed digest with this binding. */
final readonly class PackageEnableRequest implements \JsonSerializable {
    public function __construct(
        public string $packageDigest,
        public DeploymentBinding $binding,
        public bool $acknowledgedSecurityDelta
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageDigest', 'binding', 'acknowledgedSecurityDelta']) || array_diff(['packageDigest', 'binding', 'acknowledgedSecurityDelta'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageDigest'],
            DeploymentBinding::fromArray($data['binding']),
            $data['acknowledgedSecurityDelta']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
