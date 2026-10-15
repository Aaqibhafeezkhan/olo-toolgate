<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Task fields added to a Control invocation when a task record exists (platform-requirements.md §12.1). */
final readonly class InvocationTask implements \JsonSerializable {
    public function __construct(
        public string $taskId,
        public TaskState $state,
        public McpTaskStatus $status,
        public int $ttlMs,
        public int $pollIntervalMs,
        public bool $cancelIfAbandoned,
        public array $transitions,
        public string $packageDigest,
        public ?string $statusMessage = null,
        public ?TaskProgress $progress = null,
        public ?int $inputDeadlineUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['taskId', 'state', 'status', 'ttlMs', 'pollIntervalMs', 'statusMessage', 'progress', 'inputDeadlineUnixMs', 'cancelIfAbandoned', 'transitions', 'packageDigest']) || array_diff(['taskId', 'state', 'status', 'ttlMs', 'pollIntervalMs', 'cancelIfAbandoned', 'transitions', 'packageDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['taskId'],
            TaskState::from($data['state']),
            McpTaskStatus::from($data['status']),
            $data['ttlMs'],
            $data['pollIntervalMs'],
            $data['cancelIfAbandoned'],
            array_map(static fn ($item) => TaskTransition::fromArray($item), $data['transitions']),
            $data['packageDigest'],
            array_key_exists('statusMessage', $data) ? $data['statusMessage'] : null,
            array_key_exists('progress', $data) ? TaskProgress::fromArray($data['progress']) : null,
            array_key_exists('inputDeadlineUnixMs', $data) ? $data['inputDeadlineUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
