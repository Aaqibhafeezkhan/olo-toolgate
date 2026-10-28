<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Freshness evidence an invocation permit carries for an MCP_PROXY tool. */
final readonly class McpPermitVerification implements \JsonSerializable {
    public function __construct(
        public McpVerificationStatus $status,
        public int $approvalSequence,
        public ?int $checkedAtUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['status', 'approvalSequence', 'checkedAtUnixMs']) || array_diff(['status', 'approvalSequence'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            McpVerificationStatus::from($data['status']),
            $data['approvalSequence'],
            array_key_exists('checkedAtUnixMs', $data) ? $data['checkedAtUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
