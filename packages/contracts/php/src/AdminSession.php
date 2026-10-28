<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Server-verified administrator portal session. */
final readonly class AdminSession implements \JsonSerializable {
    public function __construct(
        public UserRole $role,
        public ?array $permissions = null,
        public ?string $bootstrapAdminUserId = null,
        public ?string $tenantId = null,
        public ?string $tenantName = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['permissions', 'role', 'bootstrapAdminUserId', 'tenantId', 'tenantName']) || array_diff(['role'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            UserRole::from($data['role']),
            array_key_exists('permissions', $data) ? array_map(static fn ($item) => $item, $data['permissions']) : null,
            array_key_exists('bootstrapAdminUserId', $data) ? $data['bootstrapAdminUserId'] : null,
            array_key_exists('tenantId', $data) ? $data['tenantId'] : null,
            array_key_exists('tenantName', $data) ? $data['tenantName'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
