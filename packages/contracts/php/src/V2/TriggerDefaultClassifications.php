<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tenant fail-closed defaults for unlabelled data, per integration kind (plan 12.3). A default may be raised, never lowered below the shipped value. */
final readonly class TriggerDefaultClassifications implements \JsonSerializable {
    public function __construct(
        public TriggerClassification $sdkPackage,
        public TriggerClassification $mcpProxy,
        public TriggerClassification $external,
        public TriggerClassification $unknown
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sdkPackage', 'mcpProxy', 'external', 'unknown']) || array_diff(['sdkPackage', 'mcpProxy', 'external', 'unknown'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerClassification::from($data['sdkPackage']),
            TriggerClassification::from($data['mcpProxy']),
            TriggerClassification::from($data['external']),
            TriggerClassification::from($data['unknown'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
