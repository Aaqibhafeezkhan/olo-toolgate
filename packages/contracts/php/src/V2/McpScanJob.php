<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A scan of one registration on one Tool Host of its binding Device Group. */
final readonly class McpScanJob implements \JsonSerializable {
    public function __construct(
        public string $scanId,
        public string $slug,
        public McpScanPurpose $purpose,
        public McpScanState $state,
        public int $createdAtUnixMs,
        public int $deadlineUnixMs,
        public ?string $hostId = null,
        public ?int $approvalSequence = null,
        public ?int $snapshotRevision = null,
        public ?McpCheckFailure $failure = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['scanId', 'slug', 'purpose', 'state', 'hostId', 'createdAtUnixMs', 'deadlineUnixMs', 'approvalSequence', 'snapshotRevision', 'failure']) || array_diff(['scanId', 'slug', 'purpose', 'state', 'createdAtUnixMs', 'deadlineUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['scanId'],
            $data['slug'],
            McpScanPurpose::from($data['purpose']),
            McpScanState::from($data['state']),
            $data['createdAtUnixMs'],
            $data['deadlineUnixMs'],
            array_key_exists('hostId', $data) ? $data['hostId'] : null,
            array_key_exists('approvalSequence', $data) ? $data['approvalSequence'] : null,
            array_key_exists('snapshotRevision', $data) ? $data['snapshotRevision'] : null,
            array_key_exists('failure', $data) ? McpCheckFailure::from($data['failure']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
