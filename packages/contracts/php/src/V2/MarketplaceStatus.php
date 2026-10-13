<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Whether this ToolGate reads an OLO Orgs Marketplace. The id, origin and keys are environment-owned settings, absent when disabled. */
final readonly class MarketplaceStatus implements \JsonSerializable {
    public function __construct(
        public bool $enabled,
        public ?string $marketplaceId = null,
        public ?string $baseUrl = null,
        public ?array $keys = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['enabled', 'marketplaceId', 'baseUrl', 'keys']) || array_diff(['enabled'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['enabled'],
            array_key_exists('marketplaceId', $data) ? $data['marketplaceId'] : null,
            array_key_exists('baseUrl', $data) ? $data['baseUrl'] : null,
            array_key_exists('keys', $data) ? array_map(static fn ($item) => MarketplaceKey::fromArray($item), $data['keys']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
