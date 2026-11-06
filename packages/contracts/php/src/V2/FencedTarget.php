<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Independently reviewed target identity and qualified target implementation. This declaration grants no authority by itself. */
final readonly class FencedTarget implements \JsonSerializable {
    public function __construct(
        public string $targetId,
        public FencedTargetClass $targetClass
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['targetId', 'targetClass']) || array_diff(['targetId', 'targetClass'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['targetId'],
            FencedTargetClass::from($data['targetClass'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
