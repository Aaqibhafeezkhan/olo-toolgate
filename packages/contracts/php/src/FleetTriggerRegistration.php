<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** A trigger export of a released package (ADR 058): how a device runs it. The device fetches the full descriptor by digest and checks it against this signed digest and the signed desired document. */
final readonly class FleetTriggerRegistration implements \JsonSerializable {
    public function __construct(
        public string $triggerId,
        public string $triggerDescriptorDigest,
        public string $runtimeId,
        public string $entryPoint,
        public LocalFileAccess $fileAccess
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['triggerId', 'triggerDescriptorDigest', 'runtimeId', 'entryPoint', 'fileAccess']) || array_diff(['triggerId', 'triggerDescriptorDigest', 'runtimeId', 'entryPoint', 'fileAccess'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['triggerId'],
            $data['triggerDescriptorDigest'],
            $data['runtimeId'],
            $data['entryPoint'],
            LocalFileAccess::from($data['fileAccess'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
