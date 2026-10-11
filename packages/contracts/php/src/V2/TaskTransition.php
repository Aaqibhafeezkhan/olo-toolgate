<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One recorded transition. */
final readonly class TaskTransition implements \JsonSerializable {
    public function __construct(
        public TaskState $from,
        public TaskState $to,
        public TransitionActor $actor,
        public int $atUnixMs,
        public ?string $reason = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['from', 'to', 'actor', 'atUnixMs', 'reason']) || array_diff(['from', 'to', 'actor', 'atUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TaskState::from($data['from']),
            TaskState::from($data['to']),
            TransitionActor::from($data['actor']),
            $data['atUnixMs'],
            array_key_exists('reason', $data) ? $data['reason'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
