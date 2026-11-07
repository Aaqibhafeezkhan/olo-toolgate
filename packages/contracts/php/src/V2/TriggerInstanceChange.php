<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One instance a mutation adds, changes or removes. */
final readonly class TriggerInstanceChange implements \JsonSerializable {
    public function __construct(
        public string $instanceId,
        public string $executorId,
        public TriggerInstanceChangeKind $change,
        public ?TriggerDesiredState $oldDesiredState = null,
        public ?TriggerDesiredState $newDesiredState = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['instanceId', 'executorId', 'change', 'oldDesiredState', 'newDesiredState']) || array_diff(['instanceId', 'executorId', 'change'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['instanceId'],
            $data['executorId'],
            TriggerInstanceChangeKind::from($data['change']),
            array_key_exists('oldDesiredState', $data) ? TriggerDesiredState::from($data['oldDesiredState']) : null,
            array_key_exists('newDesiredState', $data) ? TriggerDesiredState::from($data['newDesiredState']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
