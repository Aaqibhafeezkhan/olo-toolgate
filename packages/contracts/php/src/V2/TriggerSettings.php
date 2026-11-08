<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tenant trigger settings (GET and PUT /api/control/v1/triggers/settings). */
final readonly class TriggerSettings implements \JsonSerializable {
    public function __construct(
        public int $eventRetentionDays,
        public TriggerDefaultClassifications $defaultClassification
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['eventRetentionDays', 'defaultClassification']) || array_diff(['eventRetentionDays', 'defaultClassification'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['eventRetentionDays'],
            TriggerDefaultClassifications::fromArray($data['defaultClassification'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
