<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Management grants belong to group-assigned Management Roles only. */
final readonly class EnterpriseManagementRule implements \JsonSerializable {
    public function __construct(
        public array $actions,
        public EnterpriseGroupType $groupType,
        public GroupSelection $groups,
        public array $grantableScopes,
        public EnterpriseConditions $conditions,
        public ?bool $ownDevices = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['actions', 'groupType', 'groups', 'grantableScopes', 'conditions', 'ownDevices']) || array_diff(['actions', 'groupType', 'groups', 'grantableScopes', 'conditions'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => $item, $data['actions']),
            EnterpriseGroupType::from($data['groupType']),
            GroupSelection::fromArray($data['groups']),
            array_map(static fn ($item) => EnterpriseScope::fromArray($item), $data['grantableScopes']),
            EnterpriseConditions::fromArray($data['conditions']),
            array_key_exists('ownDevices', $data) ? $data['ownDevices'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
