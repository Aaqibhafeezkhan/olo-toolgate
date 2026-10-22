<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A registered upstream MCP server of the routed tenant. */
final readonly class McpServerRegistration implements \JsonSerializable {
    public function __construct(
        public string $slug,
        public string $url,
        public McpUpstreamAuth $auth,
        public string $bindingId,
        public bool $strict,
        public McpServerState $state,
        public int $revision,
        public int $createdAtUnixMs,
        public McpVerification $verification,
        public ?int $latestSnapshotRevision = null,
        public ?int $approvalSequence = null,
        public ?McpQuarantine $quarantine = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['slug', 'url', 'auth', 'bindingId', 'strict', 'state', 'revision', 'createdAtUnixMs', 'latestSnapshotRevision', 'approvalSequence', 'verification', 'quarantine']) || array_diff(['slug', 'url', 'auth', 'bindingId', 'strict', 'state', 'revision', 'createdAtUnixMs', 'verification'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['slug'],
            $data['url'],
            McpUpstreamAuth::fromArray($data['auth']),
            $data['bindingId'],
            $data['strict'],
            McpServerState::from($data['state']),
            $data['revision'],
            $data['createdAtUnixMs'],
            McpVerification::fromArray($data['verification']),
            array_key_exists('latestSnapshotRevision', $data) ? $data['latestSnapshotRevision'] : null,
            array_key_exists('approvalSequence', $data) ? $data['approvalSequence'] : null,
            array_key_exists('quarantine', $data) ? McpQuarantine::fromArray($data['quarantine']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
