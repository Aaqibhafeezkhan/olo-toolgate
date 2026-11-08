<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declarative assignment resource (plan 9.1). */
final readonly class TriggerAssignment implements \JsonSerializable {
    public function __construct(
        public string $apiVersion,
        public string $kind,
        public TriggerAssignmentMetadata $metadata,
        public TriggerAssignmentSpec $spec,
        public TriggerAssignmentStatus $status
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['apiVersion', 'kind', 'metadata', 'spec', 'status']) || array_diff(['apiVersion', 'kind', 'metadata', 'spec', 'status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['apiVersion'] !== 'toolgate.olo/v1') {
            throw new \InvalidArgumentException('apiVersion must be toolgate.olo/v1');
        }
        if ($data['kind'] !== 'TriggerAssignment') {
            throw new \InvalidArgumentException('kind must be TriggerAssignment');
        }
        return new self(
            $data['apiVersion'],
            $data['kind'],
            TriggerAssignmentMetadata::fromArray($data['metadata']),
            TriggerAssignmentSpec::fromArray($data['spec']),
            TriggerAssignmentStatus::fromArray($data['status'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
