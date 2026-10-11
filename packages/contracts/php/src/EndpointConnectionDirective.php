<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Server-authoritative response to a device presence ping; presence never grants tool access. */
final readonly class EndpointConnectionDirective implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public EndpointState $state,
        public EndpointConnectionAction $action,
        public int $reportSequence,
        public int $nextIntervalMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'state', 'action', 'reportSequence', 'nextIntervalMs']) || array_diff(['deviceId', 'state', 'action', 'reportSequence', 'nextIntervalMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deviceId'],
            EndpointState::from($data['state']),
            EndpointConnectionAction::from($data['action']),
            $data['reportSequence'],
            $data['nextIntervalMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
