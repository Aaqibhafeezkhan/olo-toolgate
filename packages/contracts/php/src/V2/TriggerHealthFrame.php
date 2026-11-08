<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** From reportHealth. */
final readonly class TriggerHealthFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public TriggerHealthStatus $status,
        public ?string $reason = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'status', 'reason']) || array_diff(['kind', 'status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'HEALTH') {
            throw new \InvalidArgumentException('kind must be HEALTH');
        }
        return new self(
            $data['kind'],
            TriggerHealthStatus::from($data['status']),
            array_key_exists('reason', $data) ? $data['reason'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
