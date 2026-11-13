<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Scope, reason and resolved state changes for a trigger assignment lifecycle action. */
final readonly class ControlTriggerLifecycleEvidence implements \JsonSerializable {
    public function __construct(
        public \OloLabs\ToolGate\Contracts\V2\TriggerLifecycleAction $action,
        public string $scopeKind,
        public array $scopeIds,
        public string $reason,
        public array $changes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['action', 'scopeKind', 'scopeIds', 'reason', 'changes']) || array_diff(['action', 'scopeKind', 'scopeIds', 'reason', 'changes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            \OloLabs\ToolGate\Contracts\V2\TriggerLifecycleAction::from($data['action']),
            $data['scopeKind'],
            array_map(static fn ($item) => $item, $data['scopeIds']),
            $data['reason'],
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\V2\TriggerInstanceChange::fromArray($item), $data['changes'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
