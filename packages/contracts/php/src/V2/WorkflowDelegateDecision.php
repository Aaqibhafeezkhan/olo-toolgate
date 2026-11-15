<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Bounded delegate model proposal; never execution authority. */
final readonly class WorkflowDelegateDecision implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $agentId,
        public string $input
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'agentId', 'input']) || array_diff(['kind', 'agentId', 'input'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'delegate') {
            throw new \InvalidArgumentException('kind must be delegate');
        }
        return new self(
            $data['kind'],
            $data['agentId'],
            $data['input']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
