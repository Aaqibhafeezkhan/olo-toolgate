<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A trigger export of an uploaded package version (plan 10.2). */
final readonly class TriggerDefinitionRecord implements \JsonSerializable {
    public function __construct(
        public string $triggerId,
        public string $packageId,
        public string $packageVersion,
        public string $packageDigest,
        public string $triggerDescriptorDigest,
        public TriggerDescriptor $descriptor,
        public array $securityDelta
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['triggerId', 'packageId', 'packageVersion', 'packageDigest', 'triggerDescriptorDigest', 'descriptor', 'securityDelta']) || array_diff(['triggerId', 'packageId', 'packageVersion', 'packageDigest', 'triggerDescriptorDigest', 'descriptor', 'securityDelta'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['triggerId'],
            $data['packageId'],
            $data['packageVersion'],
            $data['packageDigest'],
            $data['triggerDescriptorDigest'],
            TriggerDescriptor::fromArray($data['descriptor']),
            array_map(static fn ($item) => SecurityDeltaItem::fromArray($item), $data['securityDelta'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
