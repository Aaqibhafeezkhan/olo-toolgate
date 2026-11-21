<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Current group membership provenance with intact persisted grants and Control-derived super-admin- entitlements for live tenant Super Admins. Derived entitlements are not stored directory grants. Candidate grants still require the complete request evaluation. */
final readonly class EnterpriseEffectiveMembership implements \JsonSerializable {
    public function __construct(
        public EnterpriseGroupType $groupType,
        public string $groupId,
        public bool $enabled,
        public array $roleIds,
        public array $grants
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['groupType', 'groupId', 'enabled', 'roleIds', 'grants']) || array_diff(['groupType', 'groupId', 'enabled', 'roleIds', 'grants'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            EnterpriseGroupType::from($data['groupType']),
            $data['groupId'],
            $data['enabled'],
            array_map(static fn ($item) => $item, $data['roleIds']),
            array_map(static fn ($item) => ControlAccessGrant::fromArray($item), $data['grants'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
