<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Structural status for one readable trigger assignment, without configuration or source event payloads. */
final readonly class ApplicationTriggerStatus implements \JsonSerializable {
    public function __construct(
        public string $assignmentId,
        public TriggerAssignmentStatus $status
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['assignmentId', 'status']) || array_diff(['assignmentId', 'status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['assignmentId'],
            TriggerAssignmentStatus::fromArray($data['status'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
