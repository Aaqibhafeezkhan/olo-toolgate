<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Tenant-scoped agents configuration record. Not a runtime credential or policy grant. */
final readonly class ControlAgent implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $name,
        public bool $enabled,
        public int $revision,
        public string $ownerUserId,
        public ?string $slug = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'name', 'slug', 'enabled', 'revision', 'ownerUserId']) || array_diff(['id', 'name', 'enabled', 'revision', 'ownerUserId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['name'],
            $data['enabled'],
            $data['revision'],
            $data['ownerUserId'],
            array_key_exists('slug', $data) ? $data['slug'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
