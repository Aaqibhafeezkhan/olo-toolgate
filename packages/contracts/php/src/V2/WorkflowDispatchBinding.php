<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Profile declaration for WorkflowDispatchBinding; eligibility and configuration confer no runtime authority. */
final readonly class WorkflowDispatchBinding implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $subscriptionUid,
        public string $sourceAssignmentUid,
        public string $workflowId,
        public string $workflowVersionId,
        public string $serviceAgentId,
        public string $serviceAgentGroupId,
        public string $messagePointer,
        public WorkflowRemediationMode $remediationMode
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'subscriptionUid', 'sourceAssignmentUid', 'workflowId', 'workflowVersionId', 'serviceAgentId', 'serviceAgentGroupId', 'messagePointer', 'remediationMode']) || array_diff(['id', 'subscriptionUid', 'sourceAssignmentUid', 'workflowId', 'workflowVersionId', 'serviceAgentId', 'serviceAgentGroupId', 'messagePointer', 'remediationMode'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['subscriptionUid'],
            $data['sourceAssignmentUid'],
            $data['workflowId'],
            $data['workflowVersionId'],
            $data['serviceAgentId'],
            $data['serviceAgentGroupId'],
            $data['messagePointer'],
            WorkflowRemediationMode::from($data['remediationMode'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
