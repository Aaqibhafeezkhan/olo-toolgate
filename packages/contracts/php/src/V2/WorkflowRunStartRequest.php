<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explicit text input for one published bot. Backend derives service group and configured mode; current authority and idempotency remain required. */
final readonly class WorkflowRunStartRequest implements \JsonSerializable {
    public function __construct(
        public string $profileDigest,
        public int $expectedGeneration,
        public string $entryAgentId,
        public string $message,
        public ?string $dispatchBindingId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileDigest', 'expectedGeneration', 'entryAgentId', 'message', 'dispatchBindingId']) || array_diff(['profileDigest', 'expectedGeneration', 'entryAgentId', 'message'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['profileDigest'],
            $data['expectedGeneration'],
            $data['entryAgentId'],
            $data['message'],
            array_key_exists('dispatchBindingId', $data) ? $data['dispatchBindingId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
