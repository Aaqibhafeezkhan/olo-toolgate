<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Executor to Control: frames of one admitted attempt, bound to the invocation and the fencing token its permit consumption returned. Redelivery of the same sequence is idempotent. droppedFrames counts frames the executor discarded under its own bounds. */
final readonly class RunEventBatch implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public int $attempt,
        public array $events,
        public ?int $droppedFrames = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'attempt', 'events', 'droppedFrames']) || array_diff(['invocationId', 'fencingToken', 'attempt', 'events'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            $data['attempt'],
            array_map(static fn ($item) => RunEventFrame::fromArray($item), $data['events']),
            array_key_exists('droppedFrames', $data) ? $data['droppedFrames'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
