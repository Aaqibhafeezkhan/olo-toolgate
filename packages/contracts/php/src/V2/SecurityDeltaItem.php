<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One security delta. UNCLASSIFIED counts as widening. */
final readonly class SecurityDeltaItem implements \JsonSerializable {
    public function __construct(
        public SecurityDeltaKind $kind,
        public bool $widening,
        public string $summary,
        public ?string $toolId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'toolId', 'widening', 'summary']) || array_diff(['kind', 'widening', 'summary'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            SecurityDeltaKind::from($data['kind']),
            $data['widening'],
            $data['summary'],
            array_key_exists('toolId', $data) ? $data['toolId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
