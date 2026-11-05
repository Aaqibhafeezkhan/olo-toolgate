<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Fresh-build preflight for tools declaring downstream idempotency. Eligibility is configured capability only; each retry independently needs qualified complete history, current policy/consent and its original window. No key, receipt or credential is exposed. */
final readonly class ToolRecoveryReadiness implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $toolId,
        public string $name,
        public bool $automaticResendEligible,
        public \OloLabs\ToolGate\Contracts\ReconciliationMethod $effectiveMethod,
        public RecoveryReadinessReason $reason,
        public array $routes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'toolId', 'name', 'automaticResendEligible', 'effectiveMethod', 'reason', 'routes']) || array_diff(['tenantId', 'toolId', 'name', 'automaticResendEligible', 'effectiveMethod', 'reason', 'routes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['toolId'],
            $data['name'],
            $data['automaticResendEligible'],
            \OloLabs\ToolGate\Contracts\ReconciliationMethod::from($data['effectiveMethod']),
            RecoveryReadinessReason::from($data['reason']),
            array_map(static fn ($item) => RouteRecoveryReadiness::fromArray($item), $data['routes'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
