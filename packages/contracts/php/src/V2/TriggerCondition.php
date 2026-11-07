<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One instance condition. */
final readonly class TriggerCondition implements \JsonSerializable {
    public function __construct(
        public TriggerConditionType $type,
        public TriggerConditionStatus $status,
        public string $reason,
        public string $lastTransitionTime,
        public ?string $message = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['type', 'status', 'reason', 'message', 'lastTransitionTime']) || array_diff(['type', 'status', 'reason', 'lastTransitionTime'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            TriggerConditionType::from($data['type']),
            TriggerConditionStatus::from($data['status']),
            $data['reason'],
            $data['lastTransitionTime'],
            array_key_exists('message', $data) ? $data['message'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
