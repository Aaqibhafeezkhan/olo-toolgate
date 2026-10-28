<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Configured SDK and current authorized authoring contexts; availability grants no authority. */
final readonly class SdkBuilderStatus implements \JsonSerializable {
    public function __construct(
        public string $sdkVersion,
        public bool $configured,
        public int $maxSourceCharacters,
        public array $groups
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sdkVersion', 'configured', 'maxSourceCharacters', 'groups']) || array_diff(['sdkVersion', 'configured', 'maxSourceCharacters', 'groups'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['sdkVersion'],
            $data['configured'],
            $data['maxSourceCharacters'],
            array_map(static fn ($item) => SdkBuilderGroup::fromArray($item), $data['groups'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
