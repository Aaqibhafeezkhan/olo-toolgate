<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Effect settings with defaults resolved. */
final readonly class ToolEffect implements \JsonSerializable {
    public function __construct(
        public EffectSafety $safety,
        public bool $destructive,
        public bool $openWorld
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['safety', 'destructive', 'openWorld']) || array_diff(['safety', 'destructive', 'openWorld'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            EffectSafety::from($data['safety']),
            $data['destructive'],
            $data['openWorld']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
