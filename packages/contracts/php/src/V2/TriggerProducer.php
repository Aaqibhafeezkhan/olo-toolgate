<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Producer identity Control attaches from the mTLS certificate, lease and signed package; never from the payload. */
final readonly class TriggerProducer implements \JsonSerializable {
    public function __construct(
        public TriggerProducerKind $kind,
        public string $assignment,
        public string $instanceId,
        public ?string $executorId = null,
        public ?array $deviceGroups = null,
        public ?int $leaseEpoch = null,
        public ?string $packageDigest = null,
        public ?string $triggerDescriptorDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'executorId', 'deviceGroups', 'assignment', 'instanceId', 'leaseEpoch', 'packageDigest', 'triggerDescriptorDigest']) || array_diff(['kind', 'assignment', 'instanceId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerProducerKind::from($data['kind']),
            $data['assignment'],
            $data['instanceId'],
            array_key_exists('executorId', $data) ? $data['executorId'] : null,
            array_key_exists('deviceGroups', $data) ? array_map(static fn ($item) => $item, $data['deviceGroups']) : null,
            array_key_exists('leaseEpoch', $data) ? $data['leaseEpoch'] : null,
            array_key_exists('packageDigest', $data) ? $data['packageDigest'] : null,
            array_key_exists('triggerDescriptorDigest', $data) ? $data['triggerDescriptorDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
