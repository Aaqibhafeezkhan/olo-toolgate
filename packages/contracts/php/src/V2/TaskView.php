<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A task as Control returns it to the Gateway for tasks/get (invocation-state-v2.md §7). result is present only in a terminal state. */
final readonly class TaskView implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public InvocationTask $task,
        public ?ResultFrame $result = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'task', 'result']) || array_diff(['invocationId', 'task'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            InvocationTask::fromArray($data['task']),
            array_key_exists('result', $data) ? ResultFrame::fromArray($data['result']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
