<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** First stdin line, exactly once. */
final readonly class TriggerStartFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $triggerId,
        public string $instanceId,
        public int $configRevision,
        public int $leaseEpoch,
        public \stdClass $config,
        public ?\stdClass $globals = null,
        public ?array $secretHandles = null,
        public ?string $checkpoint = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'triggerId', 'instanceId', 'configRevision', 'leaseEpoch', 'config', 'globals', 'secretHandles', 'checkpoint']) || array_diff(['kind', 'triggerId', 'instanceId', 'configRevision', 'leaseEpoch', 'config'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'TRIGGER_START') {
            throw new \InvalidArgumentException('kind must be TRIGGER_START');
        }
        return new self(
            $data['kind'],
            $data['triggerId'],
            $data['instanceId'],
            $data['configRevision'],
            $data['leaseEpoch'],
            (object) $data['config'],
            array_key_exists('globals', $data) ? (object) $data['globals'] : null,
            array_key_exists('secretHandles', $data) ? array_map(static fn ($item) => $item, $data['secretHandles']) : null,
            array_key_exists('checkpoint', $data) ? $data['checkpoint'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
