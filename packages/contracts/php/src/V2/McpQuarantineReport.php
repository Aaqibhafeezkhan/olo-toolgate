<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Sent by the lease holder of exactly one invocation (a call) or one scan (a refresh); Control derives the targets from that evidence and applies it only to the current approval. */
final readonly class McpQuarantineReport implements \JsonSerializable {
    public function __construct(
        public string $fencingToken,
        public McpQuarantineReason $reason,
        public ?string $invocationId = null,
        public ?string $scanId = null,
        public ?string $observedDigest = null,
        public ?McpServerInfo $observedServerInfo = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'scanId', 'fencingToken', 'reason', 'observedDigest', 'observedServerInfo']) || array_diff(['fencingToken', 'reason'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['fencingToken'],
            McpQuarantineReason::from($data['reason']),
            array_key_exists('invocationId', $data) ? $data['invocationId'] : null,
            array_key_exists('scanId', $data) ? $data['scanId'] : null,
            array_key_exists('observedDigest', $data) ? $data['observedDigest'] : null,
            array_key_exists('observedServerInfo', $data) ? McpServerInfo::fromArray($data['observedServerInfo']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
