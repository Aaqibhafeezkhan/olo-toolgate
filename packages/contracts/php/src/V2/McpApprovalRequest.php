<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Approves tools of one snapshot; at most 64, the package and deployment limit. */
final readonly class McpApprovalRequest implements \JsonSerializable {
    public function __construct(
        public int $snapshotRevision,
        public array $tools
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['snapshotRevision', 'tools']) || array_diff(['snapshotRevision', 'tools'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['snapshotRevision'],
            array_map(static fn ($item) => McpToolApproval::fromArray($item), $data['tools'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
