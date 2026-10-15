<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** SaaS connection the package needs (platform-requirements.md §14.3). */
final readonly class ConnectionRequirement implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $provider,
        public array $scopes
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'provider', 'scopes']) || array_diff(['id', 'provider', 'scopes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['provider'],
            array_map(static fn ($item) => $item, $data['scopes'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
