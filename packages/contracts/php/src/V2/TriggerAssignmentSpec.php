<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Desired assignment of one trigger definition (plan 9.1). */
final readonly class TriggerAssignmentSpec implements \JsonSerializable {
    public function __construct(
        public string $trigger,
        public string $packageVersion,
        public TriggerTarget $target,
        public \stdClass $config,
        public TriggerDesiredState $desiredState,
        public ?\stdClass $globals = null,
        public ?TriggerRolloutStrategy $strategy = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['trigger', 'packageVersion', 'target', 'config', 'globals', 'desiredState', 'strategy']) || array_diff(['trigger', 'packageVersion', 'target', 'config', 'desiredState'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['trigger'],
            $data['packageVersion'],
            TriggerTarget::fromArray($data['target']),
            (object) $data['config'],
            TriggerDesiredState::from($data['desiredState']),
            array_key_exists('globals', $data) ? (object) $data['globals'] : null,
            array_key_exists('strategy', $data) ? TriggerRolloutStrategy::fromArray($data['strategy']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
