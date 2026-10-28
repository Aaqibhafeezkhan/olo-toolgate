<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** The latest check of a registration against its approval; a failure replaces an earlier success. */
final readonly class McpVerification implements \JsonSerializable {
    public function __construct(
        public McpVerificationStatus $status,
        public ?int $approvalSequence = null,
        public ?int $checkedAtUnixMs = null,
        public ?McpCheckFailure $failure = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['status', 'approvalSequence', 'checkedAtUnixMs', 'failure']) || array_diff(['status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            McpVerificationStatus::from($data['status']),
            array_key_exists('approvalSequence', $data) ? $data['approvalSequence'] : null,
            array_key_exists('checkedAtUnixMs', $data) ? $data['checkedAtUnixMs'] : null,
            array_key_exists('failure', $data) ? McpCheckFailure::from($data['failure']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
