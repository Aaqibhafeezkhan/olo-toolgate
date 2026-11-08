<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Result of an assignment mutation, or of its dry run. */
final readonly class TriggerAssignmentResult implements \JsonSerializable {
    public function __construct(
        public TriggerAssignment $assignment,
        public bool $dryRun,
        public array $changes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['assignment', 'dryRun', 'changes']) || array_diff(['assignment', 'dryRun', 'changes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerAssignment::fromArray($data['assignment']),
            $data['dryRun'],
            array_map(static fn ($item) => TriggerInstanceChange::fromArray($item), $data['changes'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
