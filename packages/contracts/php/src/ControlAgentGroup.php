<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Tenant-scoped Agent membership and compatible actor/management role assignments. */
final readonly class ControlAgentGroup implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $name,
        public bool $enabled,
        public int $revision,
        public array $agentIds,
        public array $roleIds,
        public ?string $slug = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'name', 'slug', 'enabled', 'revision', 'agentIds', 'roleIds']) || array_diff(['id', 'name', 'enabled', 'revision', 'agentIds', 'roleIds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['name'],
            $data['enabled'],
            $data['revision'],
            array_map(static fn ($item) => $item, $data['agentIds']),
            array_map(static fn ($item) => $item, $data['roleIds']),
            array_key_exists('slug', $data) ? $data['slug'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
