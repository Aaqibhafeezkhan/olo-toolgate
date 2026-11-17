<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Versioned, bounded JSON topology for the installed generic LangGraph compiler. Structure alone establishes no execution authority. */
final readonly class WorkflowJsonGraph implements \JsonSerializable {
    public function __construct(
        public int $schemaVersion,
        public array $nodes,
        public array $edges,
        public \stdClass $output,
        public \stdClass $outputSchema
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['schemaVersion', 'nodes', 'edges', 'output', 'outputSchema']) || array_diff(['schemaVersion', 'nodes', 'edges', 'output', 'outputSchema'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['schemaVersion'] !== 1) {
            throw new \InvalidArgumentException('schemaVersion must be 1');
        }
        return new self(
            $data['schemaVersion'],
            array_map(static fn ($item) => WorkflowJsonNode::fromArray($item), $data['nodes']),
            array_map(static fn ($item) => WorkflowJsonEdge::fromArray($item), $data['edges']),
            (object) $data['output'],
            (object) $data['outputSchema']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
