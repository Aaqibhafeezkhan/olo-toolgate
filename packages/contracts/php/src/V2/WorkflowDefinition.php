<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Profile declaration for WorkflowDefinition; eligibility and configuration confer no runtime authority. */
final readonly class WorkflowDefinition implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $versionId,
        public string $workflowGroupId,
        public string $modelGroupId,
        public string $systemPrompt,
        public array $contextSources,
        public \stdClass $parameterSchema,
        public \stdClass $resultSchema,
        public WorkflowExecutionLimits $limits,
        public array $agents
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'versionId', 'workflowGroupId', 'modelGroupId', 'systemPrompt', 'contextSources', 'parameterSchema', 'resultSchema', 'limits', 'agents']) || array_diff(['id', 'versionId', 'workflowGroupId', 'modelGroupId', 'systemPrompt', 'contextSources', 'parameterSchema', 'resultSchema', 'limits', 'agents'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['versionId'],
            $data['workflowGroupId'],
            $data['modelGroupId'],
            $data['systemPrompt'],
            array_map(static fn ($item) => WorkflowContextSource::fromArray($item), $data['contextSources']),
            (object) $data['parameterSchema'],
            (object) $data['resultSchema'],
            WorkflowExecutionLimits::fromArray($data['limits']),
            array_map(static fn ($item) => WorkflowAgentBinding::fromArray($item), $data['agents'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
