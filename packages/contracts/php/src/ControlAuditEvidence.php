<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Authorization facts of an invocation event as separate fields (OLO M4.4). Never arguments, payloads or credentials. */
final readonly class ControlAuditEvidence implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $mode,
        public string $toolId,
        public string $operation,
        public int $resourceCount,
        public string $resourcesDigest,
        public string $state,
        public int $authorizationEpoch,
        public int $policyRevision,
        public ?string $subjectUserId = null,
        public ?string $actorAgentId = null,
        public ?array $delegationChain = null,
        public ?string $workloadBindingId = null,
        public ?string $credentialKind = null,
        public ?array $resources = null,
        public ?Decision $decision = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'mode', 'subjectUserId', 'actorAgentId', 'delegationChain', 'workloadBindingId', 'credentialKind', 'toolId', 'operation', 'resources', 'resourceCount', 'resourcesDigest', 'decision', 'state', 'authorizationEpoch', 'policyRevision']) || array_diff(['invocationId', 'mode', 'toolId', 'operation', 'resourceCount', 'resourcesDigest', 'state', 'authorizationEpoch', 'policyRevision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['mode'],
            $data['toolId'],
            $data['operation'],
            $data['resourceCount'],
            $data['resourcesDigest'],
            $data['state'],
            $data['authorizationEpoch'],
            $data['policyRevision'],
            array_key_exists('subjectUserId', $data) ? $data['subjectUserId'] : null,
            array_key_exists('actorAgentId', $data) ? $data['actorAgentId'] : null,
            array_key_exists('delegationChain', $data) ? array_map(static fn ($item) => $item, $data['delegationChain']) : null,
            array_key_exists('workloadBindingId', $data) ? $data['workloadBindingId'] : null,
            array_key_exists('credentialKind', $data) ? $data['credentialKind'] : null,
            array_key_exists('resources', $data) ? array_map(static fn ($item) => $item, $data['resources']) : null,
            array_key_exists('decision', $data) ? Decision::from($data['decision']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
