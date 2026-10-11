<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One effective field and the layer it came from. */
final readonly class EffectiveValue implements \JsonSerializable {
    public function __construct(
        public string $field,
        public mixed $value,
        public ConfigLayer $layer,
        public ?ConfigLayer $cappedBy = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['field', 'value', 'layer', 'cappedBy']) || array_diff(['field', 'value', 'layer'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['field'],
            $data['value'],
            ConfigLayer::from($data['layer']),
            array_key_exists('cappedBy', $data) ? ConfigLayer::from($data['cappedBy']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
