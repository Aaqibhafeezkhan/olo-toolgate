<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Trigger export (ADR 058, plan 5.9): the signed trigger descriptor plus packaging members outside the digest. Trigger names share the package export namespace with tools. */
final readonly class TriggerDefinition implements \JsonSerializable {
    public function __construct(
        public TriggerDescriptor $descriptor,
        public string $triggerDescriptorDigest,
        public string $runtimeId,
        public string $entryPoint,
        public array $artifactIds
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['descriptor', 'triggerDescriptorDigest', 'runtimeId', 'entryPoint', 'artifactIds']) || array_diff(['descriptor', 'triggerDescriptorDigest', 'runtimeId', 'entryPoint', 'artifactIds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerDescriptor::fromArray($data['descriptor']),
            $data['triggerDescriptorDigest'],
            $data['runtimeId'],
            $data['entryPoint'],
            array_map(static fn ($item) => $item, $data['artifactIds'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
