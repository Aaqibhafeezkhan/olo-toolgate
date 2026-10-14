<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Enterprise group graph. Individual ACLs and retired snapshot formats are rejected. */
final readonly class ControlSnapshot implements \JsonSerializable {
    public function __construct(
        public int $formatVersion,
        public string $tenantId,
        public int $revision,
        public array $users,
        public array $teams,
        public array $agents,
        public array $tools,
        public array $policies,
        public array $devices,
        public array $roles,
        public array $deviceGroups,
        public array $agentGroups,
        public array $toolGroups,
        public array $grants,
        public array $delegations,
        public array $agentDelegations,
        public array $bindings,
        public array $extractors,
        public array $workloadBindings,
        public array $identityBindings,
        public array $deviceEvidence,
        public ?\OloLabs\ToolGate\Contracts\V2\ControlServerSettingsPatch $settings = null,
        public ?array $requiredIdentities = null,
        public ?ControlVisitorPool $visitorPool = null,
        public ?int $reservedRecords = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['formatVersion', 'tenantId', 'revision', 'users', 'teams', 'agents', 'tools', 'policies', 'devices', 'roles', 'deviceGroups', 'agentGroups', 'toolGroups', 'grants', 'delegations', 'agentDelegations', 'bindings', 'extractors', 'workloadBindings', 'identityBindings', 'deviceEvidence', 'settings', 'requiredIdentities', 'visitorPool', 'reservedRecords']) || array_diff(['formatVersion', 'tenantId', 'revision', 'users', 'teams', 'agents', 'tools', 'policies', 'devices', 'roles', 'deviceGroups', 'agentGroups', 'toolGroups', 'grants', 'delegations', 'agentDelegations', 'bindings', 'extractors', 'workloadBindings', 'identityBindings', 'deviceEvidence'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['formatVersion'],
            $data['tenantId'],
            $data['revision'],
            array_map(static fn ($item) => ControlUser::fromArray($item), $data['users']),
            array_map(static fn ($item) => ControlTeam::fromArray($item), $data['teams']),
            array_map(static fn ($item) => ControlAgent::fromArray($item), $data['agents']),
            array_map(static fn ($item) => ControlTool::fromArray($item), $data['tools']),
            array_map(static fn ($item) => ControlPolicy::fromArray($item), $data['policies']),
            array_map(static fn ($item) => ControlDevice::fromArray($item), $data['devices']),
            array_map(static fn ($item) => ControlRole::fromArray($item), $data['roles']),
            array_map(static fn ($item) => ControlDeviceGroup::fromArray($item), $data['deviceGroups']),
            array_map(static fn ($item) => ControlAgentGroup::fromArray($item), $data['agentGroups']),
            array_map(static fn ($item) => ControlToolGroup::fromArray($item), $data['toolGroups']),
            array_map(static fn ($item) => ControlAccessGrant::fromArray($item), $data['grants']),
            array_map(static fn ($item) => ControlDelegation::fromArray($item), $data['delegations']),
            array_map(static fn ($item) => ControlAgentDelegation::fromArray($item), $data['agentDelegations']),
            array_map(static fn ($item) => ControlExecutionBinding::fromArray($item), $data['bindings']),
            array_map(static fn ($item) => ControlResourceExtractor::fromArray($item), $data['extractors']),
            array_map(static fn ($item) => ControlWorkloadBinding::fromArray($item), $data['workloadBindings']),
            array_map(static fn ($item) => ControlIdentityBinding::fromArray($item), $data['identityBindings']),
            array_map(static fn ($item) => ControlDeviceEvidence::fromArray($item), $data['deviceEvidence']),
            array_key_exists('settings', $data) ? \OloLabs\ToolGate\Contracts\V2\ControlServerSettingsPatch::fromArray($data['settings']) : null,
            array_key_exists('requiredIdentities', $data) ? array_map(static fn ($item) => ControlRequiredIdentity::fromArray($item), $data['requiredIdentities']) : null,
            array_key_exists('visitorPool', $data) ? ControlVisitorPool::fromArray($data['visitorPool']) : null,
            array_key_exists('reservedRecords', $data) ? $data['reservedRecords'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
