<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Logical trigger mappings restored to destination identities. */
final readonly class WorkflowBundleConnection implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $assignment,
        public string $subscription,
        public TriggerAssignmentSpec $assignmentSpec,
        public TriggerSubscriptionSpec $subscriptionSpec
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'assignment', 'subscription', 'assignmentSpec', 'subscriptionSpec']) || array_diff(['id', 'assignment', 'subscription', 'assignmentSpec', 'subscriptionSpec'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['assignment'],
            $data['subscription'],
            TriggerAssignmentSpec::fromArray($data['assignmentSpec']),
            TriggerSubscriptionSpec::fromArray($data['subscriptionSpec'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
