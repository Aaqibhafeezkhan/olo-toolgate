<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Permit claims. exp is the short admission window, not the execution deadline. */
final readonly class InvocationPermitClaims implements \JsonSerializable {
    public function __construct(
        public string $jti,
        public string $iss,
        public string $aud,
        public int $iat,
        public int $nbf,
        public int $exp,
        public string $nonce,
        public string $invocationId,
        public int $attempt,
        public PermitIdentity $identity,
        public PermitTarget $target,
        public string $catalogRevision,
        public string $argumentsDigest,
        public string $action,
        public array $resources,
        public EffectSafety $effectSafety,
        public bool $requiresExecutionLedger,
        public array $approvalIds,
        public int $deadlineUnixMs,
        public ?string $taskId = null,
        public ?string $businessKeyHash = null,
        public ?KeyNamespace $businessKeyNamespace = null,
        public ?McpPermitVerification $mcpVerification = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['jti', 'iss', 'aud', 'iat', 'nbf', 'exp', 'nonce', 'invocationId', 'attempt', 'taskId', 'identity', 'target', 'catalogRevision', 'argumentsDigest', 'action', 'resources', 'effectSafety', 'requiresExecutionLedger', 'businessKeyHash', 'businessKeyNamespace', 'approvalIds', 'deadlineUnixMs', 'mcpVerification']) || array_diff(['jti', 'iss', 'aud', 'iat', 'nbf', 'exp', 'nonce', 'invocationId', 'attempt', 'identity', 'target', 'catalogRevision', 'argumentsDigest', 'action', 'resources', 'effectSafety', 'requiresExecutionLedger', 'approvalIds', 'deadlineUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['jti'],
            $data['iss'],
            $data['aud'],
            $data['iat'],
            $data['nbf'],
            $data['exp'],
            $data['nonce'],
            $data['invocationId'],
            $data['attempt'],
            PermitIdentity::fromArray($data['identity']),
            PermitTarget::fromArray($data['target']),
            $data['catalogRevision'],
            $data['argumentsDigest'],
            $data['action'],
            array_map(static fn ($item) => (object) $item, $data['resources']),
            EffectSafety::from($data['effectSafety']),
            $data['requiresExecutionLedger'],
            array_map(static fn ($item) => $item, $data['approvalIds']),
            $data['deadlineUnixMs'],
            array_key_exists('taskId', $data) ? $data['taskId'] : null,
            array_key_exists('businessKeyHash', $data) ? $data['businessKeyHash'] : null,
            array_key_exists('businessKeyNamespace', $data) ? KeyNamespace::from($data['businessKeyNamespace']) : null,
            array_key_exists('mcpVerification', $data) ? McpPermitVerification::fromArray($data['mcpVerification']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
