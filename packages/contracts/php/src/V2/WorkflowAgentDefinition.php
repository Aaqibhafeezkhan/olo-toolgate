<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Profile declaration for WorkflowAgentDefinition; eligibility and configuration confer no runtime authority. */
final readonly class WorkflowAgentDefinition implements \JsonSerializable {
    public function __construct(
        public string $agentId,
        public string $modelGroupId,
        public string $systemPrompt,
        public array $contextSources,
        public \stdClass $outputSchema
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['agentId', 'modelGroupId', 'systemPrompt', 'contextSources', 'outputSchema']) || array_diff(['agentId', 'modelGroupId', 'systemPrompt', 'contextSources', 'outputSchema'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['agentId'],
            $data['modelGroupId'],
            $data['systemPrompt'],
            array_map(static fn ($item) => WorkflowContextSource::fromArray($item), $data['contextSources']),
            (object) $data['outputSchema']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
