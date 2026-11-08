<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One resolved instance in the desired document (plan 8.1). */
final readonly class TriggerDesiredInstance implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public string $triggerId,
        public string $packageDigest,
        public string $triggerDescriptorDigest,
        public int $configRevision,
        public \stdClass $config,
        public string $bindingDigest,
        public TriggerDesiredState $desiredState
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'triggerId', 'packageDigest', 'triggerDescriptorDigest', 'configRevision', 'config', 'bindingDigest', 'desiredState']) || array_diff(['instanceId', 'triggerId', 'packageDigest', 'triggerDescriptorDigest', 'configRevision', 'config', 'bindingDigest', 'desiredState'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['triggerId'],
            $data['packageDigest'],
            $data['triggerDescriptorDigest'],
            $data['configRevision'],
            (object) $data['config'],
            $data['bindingDigest'],
            TriggerDesiredState::from($data['desiredState'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
