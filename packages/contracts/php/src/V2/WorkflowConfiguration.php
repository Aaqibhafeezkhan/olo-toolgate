<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Profile declaration for WorkflowConfiguration; eligibility and configuration confer no runtime authority. */
final readonly class WorkflowConfiguration implements \JsonSerializable {
    public function __construct(
        public array $workflowGroups,
        public array $modelGroups,
        public array $agentDefinitions,
        public array $workflows,
        public array $dispatchBindings,
        public array $modelDeployments
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['workflowGroups', 'modelGroups', 'agentDefinitions', 'workflows', 'dispatchBindings', 'modelDeployments']) || array_diff(['workflowGroups', 'modelGroups', 'agentDefinitions', 'workflows', 'dispatchBindings', 'modelDeployments'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => WorkflowGroupDefinition::fromArray($item), $data['workflowGroups']),
            array_map(static fn ($item) => WorkflowModelGroupDefinition::fromArray($item), $data['modelGroups']),
            array_map(static fn ($item) => WorkflowAgentDefinition::fromArray($item), $data['agentDefinitions']),
            array_map(static fn ($item) => WorkflowDefinition::fromArray($item), $data['workflows']),
            array_map(static fn ($item) => WorkflowDispatchBinding::fromArray($item), $data['dispatchBindings']),
            array_map(static fn ($item) => WorkflowModelDeployment::fromArray($item), $data['modelDeployments'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
