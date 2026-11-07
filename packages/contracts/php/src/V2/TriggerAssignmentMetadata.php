<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Server-assigned assignment metadata. */
final readonly class TriggerAssignmentMetadata implements \JsonSerializable {
    public function __construct(
        public string $name,
        public string $uid,
        public int $generation,
        public int $configRevision,
        public string $resourceVersion,
        public array $labels,
        public int $createdAtUnixMs,
        public int $updatedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['name', 'uid', 'generation', 'configRevision', 'resourceVersion', 'labels', 'createdAtUnixMs', 'updatedAtUnixMs']) || array_diff(['name', 'uid', 'generation', 'configRevision', 'resourceVersion', 'labels', 'createdAtUnixMs', 'updatedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['name'],
            $data['uid'],
            $data['generation'],
            $data['configRevision'],
            $data['resourceVersion'],
            array_map(static fn ($item) => TriggerLabel::fromArray($item), $data['labels']),
            $data['createdAtUnixMs'],
            $data['updatedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
