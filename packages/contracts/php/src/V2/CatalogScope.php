<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Catalog scope. */
final readonly class CatalogScope implements \JsonSerializable {
    public function __construct(
        public CatalogScopeKind $kind,
        public array $toolsets,
        public string $revision,
        public ?string $agentId = null,
        public ?string $agentGroupId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'agentId', 'agentGroupId', 'toolsets', 'revision']) || array_diff(['kind', 'toolsets', 'revision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            CatalogScopeKind::from($data['kind']),
            array_map(static fn ($item) => $item, $data['toolsets']),
            $data['revision'],
            array_key_exists('agentId', $data) ? $data['agentId'] : null,
            array_key_exists('agentGroupId', $data) ? $data['agentGroupId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
