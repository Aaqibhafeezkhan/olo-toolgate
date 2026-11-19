<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Ordered durable progress for one tenant/run; cursor advances only through returned events. */
final readonly class WorkflowRunProgressPage implements \JsonSerializable {
    public function __construct(
        public array $items,
        public int $nextCursor
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['items', 'nextCursor']) || array_diff(['items', 'nextCursor'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => WorkflowRunProgressEvent::fromArray($item), $data['items']),
            $data['nextCursor']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
