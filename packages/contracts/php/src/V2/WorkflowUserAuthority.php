<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Control-owned manual-run user and admitted resource ceiling; current grants and identity must still be checked. Never accepted from an external caller. */
final readonly class WorkflowUserAuthority implements \JsonSerializable {
    public function __construct(
        public string $userId,
        public string $principalId,
        public string $identityDigest,
        public array $humanScopes,
        public array $secretScopes,
        public array $agentScopes,
        public array $agentSecretScopes,
        public array $requiredScopes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['userId', 'principalId', 'identityDigest', 'humanScopes', 'secretScopes', 'agentScopes', 'agentSecretScopes', 'requiredScopes']) || array_diff(['userId', 'principalId', 'identityDigest', 'humanScopes', 'secretScopes', 'agentScopes', 'agentSecretScopes', 'requiredScopes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['userId'],
            $data['principalId'],
            $data['identityDigest'],
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\EnterpriseScope::fromArray($item), $data['humanScopes']),
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\EnterpriseScope::fromArray($item), $data['secretScopes']),
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\EnterpriseScope::fromArray($item), $data['agentScopes']),
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\EnterpriseScope::fromArray($item), $data['agentSecretScopes']),
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\EnterpriseScope::fromArray($item), $data['requiredScopes'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
