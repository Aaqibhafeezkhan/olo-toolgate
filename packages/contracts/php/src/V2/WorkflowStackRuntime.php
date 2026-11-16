<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackRuntime implements \JsonSerializable {
    public function __construct(
        public string $engine,
        public string $image
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['engine', 'image']) || array_diff(['engine', 'image'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['engine'] !== 'LANGGRAPH') {
            throw new \InvalidArgumentException('engine must be LANGGRAPH');
        }
        return new self(
            $data['engine'],
            $data['image']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
