<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared global. */
final readonly class GlobalDeclaration implements \JsonSerializable {
    public function __construct(
        public string $name,
        public ValueType $type,
        public bool $required,
        public bool $sensitive,
        public mixed $default = null,
        public ?string $description = null,
        public ?float $minimum = null,
        public ?float $maximum = null,
        public ?int $minLength = null,
        public ?int $maxLength = null,
        public ?string $pattern = null,
        public ?array $enum = null,
        public ?string $format = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['name', 'type', 'required', 'default', 'sensitive', 'description', 'minimum', 'maximum', 'minLength', 'maxLength', 'pattern', 'enum', 'format']) || array_diff(['name', 'type', 'required', 'sensitive'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['name'],
            ValueType::from($data['type']),
            $data['required'],
            $data['sensitive'],
            array_key_exists('default', $data) ? $data['default'] : null,
            array_key_exists('description', $data) ? $data['description'] : null,
            array_key_exists('minimum', $data) ? $data['minimum'] : null,
            array_key_exists('maximum', $data) ? $data['maximum'] : null,
            array_key_exists('minLength', $data) ? $data['minLength'] : null,
            array_key_exists('maxLength', $data) ? $data['maxLength'] : null,
            array_key_exists('pattern', $data) ? $data['pattern'] : null,
            array_key_exists('enum', $data) ? array_map(static fn ($item) => $item, $data['enum']) : null,
            array_key_exists('format', $data) ? $data['format'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
