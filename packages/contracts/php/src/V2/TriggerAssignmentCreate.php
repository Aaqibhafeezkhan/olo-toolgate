<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Body of POST /api/control/v1/triggers/assignments. */
final readonly class TriggerAssignmentCreate implements \JsonSerializable {
    public function __construct(
        public string $name,
        public TriggerAssignmentSpec $spec,
        public ?array $labels = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['name', 'labels', 'spec']) || array_diff(['name', 'spec'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['name'],
            TriggerAssignmentSpec::fromArray($data['spec']),
            array_key_exists('labels', $data) ? array_map(static fn ($item) => TriggerLabel::fromArray($item), $data['labels']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
