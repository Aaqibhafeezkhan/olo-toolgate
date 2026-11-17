<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Pure node declaration; per-run capabilities are injected by the runtime, never captured in topology. */
final readonly class WorkflowJsonNode implements \JsonSerializable {
    public function __construct(
        public string $id,
        public WorkflowJsonNodeKind $kind,
        public \stdClass $input,
        public ?string $agentId = null,
        public ?string $toolRef = null,
        public ?WorkflowAgentResultMode $resultMode = null,
        public ?WorkflowJsonBranchRoutes $routes = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'kind', 'input', 'agentId', 'toolRef', 'resultMode', 'routes']) || array_diff(['id', 'kind', 'input'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            WorkflowJsonNodeKind::from($data['kind']),
            (object) $data['input'],
            array_key_exists('agentId', $data) ? $data['agentId'] : null,
            array_key_exists('toolRef', $data) ? $data['toolRef'] : null,
            array_key_exists('resultMode', $data) ? WorkflowAgentResultMode::from($data['resultMode']) : null,
            array_key_exists('routes', $data) ? WorkflowJsonBranchRoutes::fromArray($data['routes']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
