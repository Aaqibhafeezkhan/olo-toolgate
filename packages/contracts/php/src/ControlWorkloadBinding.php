<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Individual identity binding is authentication only; permissions derive from Agent Groups. */
final readonly class ControlWorkloadBinding implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $name,
        public bool $enabled,
        public int $revision,
        public string $agentId,
        public \OloLabs\ToolGate\Contracts\V2\RequestMode $mode,
        public string $issuer,
        public string $subject,
        public string $audience,
        public string $credentialSha256,
        public int $credentialEpoch,
        public int $expiresAtUnixMs,
        public ?string $delegatedUserId = null,
        public ?string $parentBindingId = null,
        public ?int $delegatedSessionEpoch = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'name', 'enabled', 'revision', 'agentId', 'mode', 'issuer', 'subject', 'audience', 'credentialSha256', 'credentialEpoch', 'expiresAtUnixMs', 'delegatedUserId', 'parentBindingId', 'delegatedSessionEpoch']) || array_diff(['id', 'name', 'enabled', 'revision', 'agentId', 'mode', 'issuer', 'subject', 'audience', 'credentialSha256', 'credentialEpoch', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['name'],
            $data['enabled'],
            $data['revision'],
            $data['agentId'],
            \OloLabs\ToolGate\Contracts\V2\RequestMode::from($data['mode']),
            $data['issuer'],
            $data['subject'],
            $data['audience'],
            $data['credentialSha256'],
            $data['credentialEpoch'],
            $data['expiresAtUnixMs'],
            array_key_exists('delegatedUserId', $data) ? $data['delegatedUserId'] : null,
            array_key_exists('parentBindingId', $data) ? $data['parentBindingId'] : null,
            array_key_exists('delegatedSessionEpoch', $data) ? $data['delegatedSessionEpoch'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
