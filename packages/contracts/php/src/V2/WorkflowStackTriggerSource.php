<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackTriggerSource implements \JsonSerializable {
    public function __construct(
        public string $subscription,
        public string $assignment,
        public string $packageId,
        public string $version,
        public string $triggerId,
        public string $deviceGroupId,
        public \stdClass $config,
        public string $eventFilter,
        public ?string $packageDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['subscription', 'assignment', 'packageId', 'version', 'triggerId', 'deviceGroupId', 'config', 'eventFilter', 'packageDigest']) || array_diff(['subscription', 'assignment', 'packageId', 'version', 'triggerId', 'deviceGroupId', 'config', 'eventFilter'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['subscription'],
            $data['assignment'],
            $data['packageId'],
            $data['version'],
            $data['triggerId'],
            $data['deviceGroupId'],
            (object) $data['config'],
            $data['eventFilter'],
            array_key_exists('packageDigest', $data) ? $data['packageDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
