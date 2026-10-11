<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared HTTP auth. */
final readonly class HttpAuthDeclaration implements \JsonSerializable {
    public function __construct(
        public string $id,
        public AuthType $type,
        public array $scopes,
        public ?string $header = null,
        public ?string $secret = null,
        public ?string $connection = null,
        public ?string $audience = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'type', 'header', 'secret', 'connection', 'scopes', 'audience']) || array_diff(['id', 'type', 'scopes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            AuthType::from($data['type']),
            array_map(static fn ($item) => $item, $data['scopes']),
            array_key_exists('header', $data) ? $data['header'] : null,
            array_key_exists('secret', $data) ? $data['secret'] : null,
            array_key_exists('connection', $data) ? $data['connection'] : null,
            array_key_exists('audience', $data) ? $data['audience'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
