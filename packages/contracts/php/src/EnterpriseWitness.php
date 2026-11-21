<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** One complete tenant-scoped path; Team and Agent Group identifiers cannot be mixed between witnesses. A super-admin- prefixed grant identifier can denote a Control-derived live Super Admin entitlement rather than a persisted grant; its Team and current directory revision remain authoritative. */
final readonly class EnterpriseWitness implements \JsonSerializable {
    public function __construct(
        public string $bindingId,
        public array $provenance,
        public ?string $teamId = null,
        public ?string $grantId = null,
        public ?string $agentGroupId = null,
        public ?string $capabilityId = null,
        public ?string $delegationId = null,
        public ?string $serviceGrantId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['teamId', 'grantId', 'agentGroupId', 'capabilityId', 'delegationId', 'serviceGrantId', 'bindingId', 'provenance']) || array_diff(['bindingId', 'provenance'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['bindingId'],
            array_map(static fn ($item) => $item, $data['provenance']),
            array_key_exists('teamId', $data) ? $data['teamId'] : null,
            array_key_exists('grantId', $data) ? $data['grantId'] : null,
            array_key_exists('agentGroupId', $data) ? $data['agentGroupId'] : null,
            array_key_exists('capabilityId', $data) ? $data['capabilityId'] : null,
            array_key_exists('delegationId', $data) ? $data['delegationId'] : null,
            array_key_exists('serviceGrantId', $data) ? $data['serviceGrantId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
