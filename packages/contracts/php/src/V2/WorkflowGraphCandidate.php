<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Reviewed immutable graph candidate read by a verified tenant runtime service. Carries configuration only, never execution credentials. */
final readonly class WorkflowGraphCandidate implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $profileId,
        public string $profileDigest,
        public string $workflowId,
        public int $generation,
        public WorkflowGraphSelection $graph,
        public array $agentIds
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'profileId', 'profileDigest', 'workflowId', 'generation', 'graph', 'agentIds']) || array_diff(['tenantId', 'profileId', 'profileDigest', 'workflowId', 'generation', 'graph', 'agentIds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['profileId'],
            $data['profileDigest'],
            $data['workflowId'],
            $data['generation'],
            WorkflowGraphSelection::fromArray($data['graph']),
            array_map(static fn ($item) => $item, $data['agentIds'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
