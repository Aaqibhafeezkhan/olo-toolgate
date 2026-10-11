<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Business-key settings. */
final readonly class BusinessKeySettings implements \JsonSerializable {
    public function __construct(
        public bool $required,
        public KeyNamespace $namespace,
        public ?string $argument = null,
        public ?string $idempotencyHeader = null,
        public ?IdempotencyScope $idempotencyScope = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['required', 'namespace', 'argument', 'idempotencyHeader', 'idempotencyScope']) || array_diff(['required', 'namespace'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['required'],
            KeyNamespace::from($data['namespace']),
            array_key_exists('argument', $data) ? $data['argument'] : null,
            array_key_exists('idempotencyHeader', $data) ? $data['idempotencyHeader'] : null,
            array_key_exists('idempotencyScope', $data) ? IdempotencyScope::from($data['idempotencyScope']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
