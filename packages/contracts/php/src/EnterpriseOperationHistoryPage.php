<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Authorized operation snapshot and bounded generation history; a changed source revision invalidates the cursor rather than silently mixing snapshots. */
final readonly class EnterpriseOperationHistoryPage implements \JsonSerializable {
    public function __construct(
        public EnterpriseOperationOutcome $operation,
        public array $attempts,
        public array $effects,
        public bool $truncated,
        public ?string $next = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['operation', 'attempts', 'effects', 'next', 'truncated']) || array_diff(['operation', 'attempts', 'effects', 'truncated'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            EnterpriseOperationOutcome::fromArray($data['operation']),
            array_map(static fn ($item) => EnterpriseAttemptHistory::fromArray($item), $data['attempts']),
            array_map(static fn ($item) => EnterpriseEffectHistory::fromArray($item), $data['effects']),
            $data['truncated'],
            array_key_exists('next', $data) ? $data['next'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
