<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Authorized replay page after a cursor. terminal is the authoritative ledger state; truncated, droppedFrames, gaps and expired say explicitly what is not shown. */
final readonly class RunEventPage implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $state,
        public bool $terminal,
        public array $events,
        public int $nextCursor,
        public bool $more,
        public bool $truncated,
        public int $droppedFrames,
        public bool $expired,
        public array $gaps,
        public ?string $toolId = null,
        public ?string $executorId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'state', 'terminal', 'toolId', 'executorId', 'events', 'nextCursor', 'more', 'truncated', 'droppedFrames', 'expired', 'gaps']) || array_diff(['invocationId', 'state', 'terminal', 'events', 'nextCursor', 'more', 'truncated', 'droppedFrames', 'expired', 'gaps'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['state'],
            $data['terminal'],
            array_map(static fn ($item) => RunEvent::fromArray($item), $data['events']),
            $data['nextCursor'],
            $data['more'],
            $data['truncated'],
            $data['droppedFrames'],
            $data['expired'],
            array_map(static fn ($item) => RunEventGap::fromArray($item), $data['gaps']),
            array_key_exists('toolId', $data) ? $data['toolId'] : null,
            array_key_exists('executorId', $data) ? $data['executorId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
